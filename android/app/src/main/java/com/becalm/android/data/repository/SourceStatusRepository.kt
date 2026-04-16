package com.becalm.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.becalm.android.core.di.UserPrefs
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.dto.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ─── Supporting types ────────────────────────────────────────────────────────

/** Connection/sync health of a single data source. */
public enum class SourceConnectionStatus {
    NEVER_CONNECTED,
    SYNCING,
    CONNECTED,
    ERROR,
}

/**
 * Snapshot of a single data source's sync state as observed by the client.
 *
 * @param sourceType One of the [SourceType] string constants.
 * @param status     Derived connection health; see [SourceConnectionStatus].
 * @param lastSyncedAt Wall-clock instant of the most-recently completed successful sync,
 *                     or `null` when no sync has ever finished successfully.
 * @param errorMessage Human-readable error text from the most-recent failed sync,
 *                     or `null` when the current state is not [SourceConnectionStatus.ERROR].
 */
public data class SourceStatus(
    val sourceType: String,
    val status: SourceConnectionStatus,
    val lastSyncedAt: Instant?,
    val errorMessage: String?,
)

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Reactive read/write facade over per-source sync health metadata.
 *
 * ## Contract gap (spec TDY-003, SMG-002)
 * There is no `/v1/source_status` endpoint in `api-contract.yml` v1. Source status is
 * derived entirely client-side from [SyncCursorStore] + the `@UserPrefs`
 * `DataStore<Preferences>`. If a server endpoint is added in a future contract version,
 * migrate to a hybrid client/server model then; do not eagerly wire `RailwayApi` now.
 *
 * ## Status derivation
 * For each source in [SourceType.ALL]:
 * 1. `lastSyncedAt == null` AND `lastError == null` → [SourceConnectionStatus.NEVER_CONNECTED]
 * 2. `inProgress == true` → [SourceConnectionStatus.SYNCING]
 * 3. `lastError != null && lastError.isNotBlank()` → [SourceConnectionStatus.ERROR]
 * 4. Otherwise → [SourceConnectionStatus.CONNECTED]
 */
public interface SourceStatusRepository {

    /**
     * Emits the status list for all [SourceType.ALL] entries whenever any source's
     * cursor or prefs change. Order follows [SourceType.ALL] iteration order.
     */
    public fun observeAll(): Flow<List<SourceStatus>>

    /**
     * Emits the status for a single [sourceType] whenever its cursor or prefs change.
     *
     * @param sourceType One of the [SourceType] string constants.
     */
    public fun observeFor(sourceType: String): Flow<SourceStatus>

    /**
     * Records a successful sync completion for [sourceType].
     *
     * Clears the in-progress flag and any stored error, then persists [at] as the
     * last-synced-at timestamp.
     *
     * @param sourceType One of the [SourceType] string constants.
     * @param at         Instant at which the sync completed successfully.
     */
    public suspend fun recordSyncSuccess(sourceType: String, at: Instant): BecalmResult<Unit>

    /**
     * Records a sync failure for [sourceType].
     *
     * Clears the in-progress flag and persists the [error] message and [at] timestamp.
     *
     * @param sourceType One of the [SourceType] string constants.
     * @param error      Human-readable description of the failure.
     * @param at         Instant at which the failure was observed.
     */
    public suspend fun recordSyncError(
        sourceType: String,
        error: String,
        at: Instant,
    ): BecalmResult<Unit>

    /**
     * Marks [sourceType] as actively syncing.
     *
     * Sets the in-progress flag; cleared by [recordSyncSuccess] or [recordSyncError].
     *
     * @param sourceType One of the [SourceType] string constants.
     */
    public suspend fun recordSyncStart(sourceType: String): BecalmResult<Unit>

    /**
     * Atomically clears all source-status metadata for every source.
     *
     * Call during sign-out alongside cursor and preference wipes.
     */
    public suspend fun clearAll(): BecalmResult<Unit>
}

// ─── Implementation ──────────────────────────────────────────────────────────

private const val TAG = "SourceStatusRepository"

/**
 * [DataStore]-backed implementation of [SourceStatusRepository].
 *
 * ## Contract gap (spec TDY-003, SMG-002)
 * There is no `/v1/source_status` endpoint in `api-contract.yml` v1. Source status is
 * derived entirely client-side from [SyncCursorStore] + the `@UserPrefs`
 * `DataStore<Preferences>`. If a server endpoint is added in a future contract version,
 * migrate to a hybrid client/server model then; do not eagerly wire `RailwayApi` now.
 *
 * @param cursorStore   Provides cursor presence signals (cursor present = source ever synced).
 * @param userPrefs     Raw `@UserPrefs` DataStore used for source-status-specific keys that
 *                      are not part of [com.becalm.android.data.local.datastore.UserPrefsStore]'s
 *                      typed API.
 * @param logger        Structured log sink.
 */
@Singleton
public class SourceStatusRepositoryImpl @Inject constructor(
    private val cursorStore: SyncCursorStore,
    @UserPrefs private val userPrefs: DataStore<Preferences>,
    private val logger: Logger,
) : SourceStatusRepository {

    // ─── Key scheme ──────────────────────────────────────────────────────────
    // All keys are namespaced under "source_status.<source>." to avoid collisions
    // with the existing UserPrefsStore keys in the same DataStore file.

    private companion object Keys {
        fun lastSyncedAt(source: String) =
            longPreferencesKey("source_status.$source.last_synced_at")

        fun lastError(source: String) =
            stringPreferencesKey("source_status.$source.last_error")

        fun inProgress(source: String) =
            booleanPreferencesKey("source_status.$source.in_progress")
    }

    // ─── Observation ─────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<SourceStatus>> {
        val flows: List<Flow<SourceStatus>> = SourceType.ALL.map { source ->
            observeFor(source)
        }
        // combine(List<Flow>) requires at least one flow; ALL always has 7 entries.
        return combine(flows) { statuses -> statuses.toList() }
    }

    override fun observeFor(sourceType: String): Flow<SourceStatus> =
        userPrefs.data.map { prefs ->
            val lastSyncedAtMs = prefs[lastSyncedAt(sourceType)]
            val lastError = prefs[lastError(sourceType)]
            val isInProgress = prefs[inProgress(sourceType)] ?: false
            deriveStatus(sourceType, lastSyncedAtMs, lastError, isInProgress)
        }

    // ─── Writes ──────────────────────────────────────────────────────────────

    override suspend fun recordSyncSuccess(
        sourceType: String,
        at: Instant,
    ): BecalmResult<Unit> = runCatching(sourceType, "recordSyncSuccess") {
        userPrefs.edit { prefs ->
            prefs[lastSyncedAt(sourceType)] = at.toEpochMilliseconds()
            prefs.remove(lastError(sourceType))
            prefs.remove(inProgress(sourceType))
        }
        logger.d(TAG, "syncSuccess source=$sourceType at=$at")
    }

    override suspend fun recordSyncError(
        sourceType: String,
        error: String,
        at: Instant,
    ): BecalmResult<Unit> = runCatching(sourceType, "recordSyncError") {
        userPrefs.edit { prefs ->
            prefs[lastError(sourceType)] = error
            prefs[lastSyncedAt(sourceType)] = at.toEpochMilliseconds()
            prefs.remove(inProgress(sourceType))
        }
        logger.d(TAG, "syncError source=$sourceType error=$error at=$at")
    }

    override suspend fun recordSyncStart(sourceType: String): BecalmResult<Unit> =
        runCatching(sourceType, "recordSyncStart") {
            userPrefs.edit { prefs ->
                prefs[inProgress(sourceType)] = true
            }
            logger.d(TAG, "syncStart source=$sourceType")
        }

    override suspend fun clearAll(): BecalmResult<Unit> = try {
        userPrefs.edit { prefs ->
            SourceType.ALL.forEach { source ->
                prefs.remove(lastSyncedAt(source))
                prefs.remove(lastError(source))
                prefs.remove(inProgress(source))
            }
        }
        logger.d(TAG, "clearAll completed")
        BecalmResult.Success(Unit)
    } catch (e: IOException) {
        logger.e(TAG, "clearAll IO failure", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "IO error during clearAll"))
    } catch (e: Throwable) {
        logger.e(TAG, "clearAll unexpected failure", e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun deriveStatus(
        sourceType: String,
        lastSyncedAtMs: Long?,
        lastError: String?,
        isInProgress: Boolean,
    ): SourceStatus {
        val lastSyncedAt = lastSyncedAtMs?.let { Instant.fromEpochMilliseconds(it) }
        val status = when {
            lastSyncedAt == null && lastError.isNullOrBlank() -> SourceConnectionStatus.NEVER_CONNECTED
            isInProgress -> SourceConnectionStatus.SYNCING
            !lastError.isNullOrBlank() -> SourceConnectionStatus.ERROR
            else -> SourceConnectionStatus.CONNECTED
        }
        return SourceStatus(
            sourceType = sourceType,
            status = status,
            lastSyncedAt = lastSyncedAt,
            errorMessage = lastError?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun runCatching(
        sourceType: String,
        op: String,
        block: suspend () -> Unit,
    ): BecalmResult<Unit> = try {
        block()
        BecalmResult.Success(Unit)
    } catch (e: IOException) {
        logger.e(TAG, "$op source=$sourceType IO failure", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "IO error during $op"))
    } catch (e: Throwable) {
        logger.e(TAG, "$op source=$sourceType unexpected failure", e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }
}
