package com.becalm.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.di.UserPrefs
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.internal.mergeServerState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
 * ## Server-first with offline fallback (TDY-003, SMG-002)
 * When [refreshFromServer] succeeds, the authoritative state comes from
 * Railway's `GET /v1/source_status` response (api-contract.yml) and is merged into the
 * local DataStore-backed cache. When the server is unreachable or returns an error,
 * the client continues to derive state from [SyncCursorStore] + the `@UserPrefs`
 * `DataStore<Preferences>` so the Today screen remains functional offline.
 *
 * ## Status derivation (offline fallback)
 * For each source in [SourceType.PRODUCT_SOURCES]:
 * 1. `lastSyncedAt == null` AND `lastError == null` → [SourceConnectionStatus.NEVER_CONNECTED]
 * 2. `inProgress == true` → [SourceConnectionStatus.SYNCING]
 * 3. `lastError != null && lastError.isNotBlank()` → [SourceConnectionStatus.ERROR]
 * 4. Otherwise → [SourceConnectionStatus.CONNECTED]
 */
public interface SourceStatusRepository {

    /**
     * Emits the status list for every [SourceType.PRODUCT_SOURCES] entry whenever any
     * source's cursor or prefs change. Order follows [SourceType.PRODUCT_SOURCES] iteration
     * order.
     *
     * `VOICE` and `CALL_RECORDING` are deliberately excluded — voice is captured locally
     * (no OAuth connect) and call_recording is a wave-0 schema-only carve-out.
     */
    public fun observeAll(): Flow<List<SourceStatus>>

    /**
     * Emits a source_type → status map covering every [SourceType.PRODUCT_SOURCES] entry,
     * re-emitting on every cursor or prefs change.
     *
     * Convenience for consumers (e.g. [com.becalm.android.ui.today.TodayViewModel]) that
     * need keyed lookup without rebuilding an `associateBy` on every emission.
     */
    public fun observeSources(): Flow<Map<String, SourceStatus>>

    /**
     * Emits the status for a single [sourceType] whenever its cursor or prefs change.
     *
     * @param sourceType One of the [SourceType] string constants.
     */
    public fun observeFor(sourceType: String): Flow<SourceStatus>

    /**
     * Pulls the authoritative per-source state from Railway `GET /v1/source_status`
     * and merges it into the local DataStore-backed cache.
     *
     * Exactly six items are returned (voice excluded per TDY-003 / CTO Q7); any `voice`
     * entry the server sends is ignored and any missing source_type is left untouched.
     *
     * On non-2xx or network error the local cache is left intact so the offline fallback
     * remains authoritative. [BecalmResult.Failure] is returned so callers can surface
     * a banner ("일부 소스 실패 — 설정에서 확인") without losing previous state.
     */
    public suspend fun refreshFromServer(): BecalmResult<Unit>

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
 * [DataStore]-backed implementation of [SourceStatusRepository] with server-merge support.
 *
 * ## Server-first with offline fallback (TDY-003, SMG-002)
 * [refreshFromServer] calls [RailwayApi.getSourceStatus] and merges each wire item into the
 * DataStore cache via the same keys used by the offline-derivation path. When the server is
 * unreachable or returns an error, the local cache is left intact and the reactive flows
 * continue to emit derived state, so the Today screen stays functional offline.
 *
 * @param cursorStore   Provides cursor presence signals (cursor present = source ever synced).
 * @param userPrefs     Raw `@UserPrefs` DataStore used for source-status-specific keys that
 *                      are not part of [com.becalm.android.data.local.datastore.UserPrefsStore]'s
 *                      typed API.
 * @param api           Railway client for `GET /v1/source_status`.
 * @param ioDispatcher  Injected [kotlinx.coroutines.Dispatchers.IO] qualifier; no hard-coded
 *                      dispatcher references per rubric C4.
 * @param logger        Structured log sink.
 */
@Singleton
public class SourceStatusRepositoryImpl @Inject constructor(
    private val cursorStore: SyncCursorStore,
    @UserPrefs private val userPrefs: DataStore<Preferences>,
    private val api: RailwayApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
        // PRODUCT_SOURCES (6 external product sources) — NOT the schema-level ALL set.
        // ALL includes VOICE and CALL_RECORDING which either have no product tile
        // (CALL_RECORDING wave-0 carve-out) or are captured locally (VOICE), so they
        // must not appear in the Sources strip or the Today aggregate banner.
        val flows: List<Flow<SourceStatus>> = SourceType.PRODUCT_SOURCES.map { source ->
            observeFor(source)
        }
        // combine(List<Flow>) requires at least one flow; PRODUCT_SOURCES always has 6 entries.
        return combine(flows) { statuses -> statuses.toList() }
    }

    override fun observeSources(): Flow<Map<String, SourceStatus>> =
        observeAll().map { list -> list.associateBy { it.sourceType } }

    override fun observeFor(sourceType: String): Flow<SourceStatus> =
        userPrefs.data.map { prefs ->
            val lastSyncedAtMs = prefs[lastSyncedAt(sourceType)]
            val lastError = prefs[lastError(sourceType)]
            val isInProgress = prefs[inProgress(sourceType)] ?: false
            deriveStatus(sourceType, lastSyncedAtMs, lastError, isInProgress)
        }

    // ─── Server refresh (TDY-006 / TDY-008) ──────────────────────────────────

    override suspend fun refreshFromServer(): BecalmResult<Unit> = withContext(ioDispatcher) {
        try {
            val response = api.getSourceStatus()
            if (!response.isSuccessful) {
                logger.w(TAG, "refreshFromServer HTTP ${response.code()}")
                return@withContext BecalmResult.Failure(
                    BecalmError.Network(response.code(), response.message()),
                )
            }
            val body = response.body()
            if (body == null) {
                logger.w(TAG, "refreshFromServer null body")
                return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null body")),
                )
            }
            mergeServerState(
                userPrefs = userPrefs,
                items = body.sources,
                logger = logger,
                lastSyncedAt = Keys::lastSyncedAt,
                lastError = Keys::lastError,
                inProgress = Keys::inProgress,
            )
            logger.d(TAG, "refreshFromServer merged=${body.sources.size}")
            BecalmResult.Success(Unit)
        } catch (e: IOException) {
            logger.w(TAG, "refreshFromServer IO error — offline fallback remains authoritative", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "IO"))
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            logger.e(TAG, "refreshFromServer unexpected failure", e)
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    // ─── Writes ──────────────────────────────────────────────────────────────

    override suspend fun recordSyncSuccess(
        sourceType: String,
        at: Instant,
    ): BecalmResult<Unit> = runOp(sourceType, "recordSyncSuccess") {
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
    ): BecalmResult<Unit> = runOp(sourceType, "recordSyncError") {
        userPrefs.edit { prefs ->
            prefs[lastError(sourceType)] = error
            prefs[lastSyncedAt(sourceType)] = at.toEpochMilliseconds()
            prefs.remove(inProgress(sourceType))
        }
        logger.d(TAG, "syncError source=$sourceType error=$error at=$at")
    }

    override suspend fun recordSyncStart(sourceType: String): BecalmResult<Unit> =
        runOp(sourceType, "recordSyncStart") {
            userPrefs.edit { prefs ->
                prefs[inProgress(sourceType)] = true
            }
            logger.d(TAG, "syncStart source=$sourceType")
        }

    override suspend fun clearAll(): BecalmResult<Unit> = try {
        userPrefs.edit { prefs ->
            // Intentionally uses the schema-wide ALL set (not PRODUCT_SOURCES) so sign-out
            // wipes any stale keys written for VOICE/CALL_RECORDING in an earlier build —
            // leaving them behind would leak per-user sync metadata across accounts.
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
        e.rethrowIfCancellation()
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

    /**
     * try/catch wrapper replacing the previous `runCatching`-style helper. Re-throws
     * [kotlinx.coroutines.CancellationException] via [rethrowIfCancellation] so structured
     * concurrency cancellation propagates instead of being swallowed into [BecalmError.Unknown].
     */
    private suspend fun runOp(
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
        e.rethrowIfCancellation()
        logger.e(TAG, "$op source=$sourceType unexpected failure", e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }
}

