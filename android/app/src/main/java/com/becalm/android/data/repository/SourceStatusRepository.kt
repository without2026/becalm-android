package com.becalm.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
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
     * order. Current product-facing set includes `VOICE` and excludes `CALL_RECORDING`.
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
     * The product-facing status projection covers the seven user-facing sources
     * (`voice + gmail + outlook_mail + naver_imap + daum_imap + google_calendar +
     * outlook_calendar`). `call_recording` remains schema-only and is ignored.
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
     * Clears the locally derived status metadata for a single [sourceType].
     *
     * Used by source-disconnect flows so the UI returns to the idle / never-connected
     * state without disturbing sibling sources.
     */
    public suspend fun clear(sourceType: String): BecalmResult<Unit>

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
    private val apiProvider: Provider<RailwayApi>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : SourceStatusRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        cursorStore: SyncCursorStore,
        userPrefs: DataStore<Preferences>,
        api: RailwayApi,
        ioDispatcher: CoroutineDispatcher,
        logger: Logger,
    ) : this(
        cursorStore = cursorStore,
        userPrefs = userPrefs,
        apiProvider = Provider { api },
        ioDispatcher = ioDispatcher,
        logger = logger,
    )

    // ─── Observation ─────────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<SourceStatus>> {
        // PRODUCT_SOURCES (7 user-facing sources) — NOT the schema-level ALL set.
        // ALL still includes CALL_RECORDING, but that remains a schema-only carve-out
        // and must not appear in the Sources strip or Today aggregate banner.
        return userPrefs.data
            .map { prefs ->
                SourceType.PRODUCT_SOURCES.map { source ->
                    prefs.toSourceStatus(source)
                }
            }
            .distinctUntilChanged()
    }

    override fun observeSources(): Flow<Map<String, SourceStatus>> =
        observeAll().map { list -> list.associateBy { it.sourceType } }

    override fun observeFor(sourceType: String): Flow<SourceStatus> =
        userPrefs.data.map { prefs ->
            prefs.toSourceStatus(sourceType)
        }.distinctUntilChanged()

    private fun Preferences.toSourceStatus(sourceType: String): SourceStatus =
        SourceStatusDeriver.derive(
            sourceType = sourceType,
            lastSyncedAtMs = this[SourceStatusPrefsKeys.lastSyncedAt(sourceType)],
            lastError = this[SourceStatusPrefsKeys.lastError(sourceType)],
            isInProgress = this[SourceStatusPrefsKeys.inProgress(sourceType)] ?: false,
        )

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
                lastSyncedAt = SourceStatusPrefsKeys::lastSyncedAt,
                lastError = SourceStatusPrefsKeys::lastError,
                inProgress = SourceStatusPrefsKeys::inProgress,
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
            prefs[SourceStatusPrefsKeys.lastSyncedAt(sourceType)] = at.toEpochMilliseconds()
            prefs.remove(SourceStatusPrefsKeys.lastError(sourceType))
            prefs.remove(SourceStatusPrefsKeys.inProgress(sourceType))
        }
        logger.d(TAG, "syncSuccess source=$sourceType at=$at")
    }

    override suspend fun recordSyncError(
        sourceType: String,
        error: String,
        at: Instant,
    ): BecalmResult<Unit> = runOp(sourceType, "recordSyncError") {
        userPrefs.edit { prefs ->
            prefs[SourceStatusPrefsKeys.lastError(sourceType)] = error
            prefs[SourceStatusPrefsKeys.lastSyncedAt(sourceType)] = at.toEpochMilliseconds()
            prefs.remove(SourceStatusPrefsKeys.inProgress(sourceType))
        }
        logger.d(TAG, "syncError source=$sourceType error=$error at=$at")
    }

    override suspend fun recordSyncStart(sourceType: String): BecalmResult<Unit> =
        runOp(sourceType, "recordSyncStart") {
            userPrefs.edit { prefs ->
                prefs[SourceStatusPrefsKeys.inProgress(sourceType)] = true
            }
            logger.d(TAG, "syncStart source=$sourceType")
        }

    override suspend fun clear(sourceType: String): BecalmResult<Unit> =
        runOp(sourceType, "clear") {
            userPrefs.edit { prefs ->
                prefs.remove(SourceStatusPrefsKeys.lastSyncedAt(sourceType))
                prefs.remove(SourceStatusPrefsKeys.lastError(sourceType))
                prefs.remove(SourceStatusPrefsKeys.inProgress(sourceType))
            }
            logger.d(TAG, "clear source=$sourceType")
        }

    override suspend fun clearAll(): BecalmResult<Unit> = try {
        userPrefs.edit { prefs ->
            // Intentionally uses the schema-wide ALL set (not PRODUCT_SOURCES) so sign-out
            // wipes any stale keys written for VOICE/CALL_RECORDING in an earlier build —
            // leaving them behind would leak per-user sync metadata across accounts.
            SourceType.ALL.forEach { source ->
                prefs.remove(SourceStatusPrefsKeys.lastSyncedAt(source))
                prefs.remove(SourceStatusPrefsKeys.lastError(source))
                prefs.remove(SourceStatusPrefsKeys.inProgress(source))
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
