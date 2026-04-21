package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.becalm.android.core.di.UserPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * System-level monotonic counters persisted to DataStore.
 *
 * Distinct from [UserPrefsStore], which holds user-editable preferences, and from
 * [SyncCursorStore], which holds per-source incremental-sync tokens. Metrics are
 * write-once-per-event counters owned by the system (workers / LLM gates), never
 * touched by settings UI. Mixing them with [UserPrefsStore] would muddy the
 * sign-out [UserPrefsStore.clearAll] semantics, hence the dedicated interface
 * even though the underlying DataStore file is shared (see implementation).
 *
 * ## Counter scheme
 * | Counter                           | Key name                       | Trigger                             |
 * |-----------------------------------|--------------------------------|-------------------------------------|
 * | `email_subject_only_skipped`      | `email_subject_only_skipped`   | `EmailSnippetBuilder` returns `SUBJECT_FALLBACK` |
 *
 * Spec ref: `.spec/email-pipeline.spec.yml:36 § EMAIL-003` —
 * "DataStore metric `email_subject_only_skipped += 1`로 모니터링".
 */
public interface MetricsStore {

    /**
     * Atomically increments `email_subject_only_skipped` by 1.
     *
     * Called by `CommitmentExtractionWorker` (or any caller that observes
     * [com.becalm.android.domain.email.SourceKind.SUBJECT_FALLBACK] from
     * [com.becalm.android.domain.email.EmailSnippetBuilder.buildSnippet]) so the
     * skip rate of subject-only emails can be monitored.
     *
     * Idempotency note: the increment is inside a single [DataStore.edit] block,
     * so concurrent callers race-safely produce N increments for N invocations.
     */
    public suspend fun incrementSubjectOnlySkipped()

    /**
     * Emits the current `email_subject_only_skipped` count, defaulting to 0 when
     * no increment has yet occurred. Re-emits on every increment.
     *
     * Surface for future debug screen / Sentry breadcrumb. No production UI
     * consumes this Flow yet — added with the counter so the read side is part
     * of the same PR contract.
     */
    public fun observeSubjectOnlySkipped(): Flow<Int>
}

// ─── Implementation ──────────────────────────────────────────────────────────

/**
 * [DataStore]-backed implementation of [MetricsStore].
 *
 * Reuses the [@UserPrefs][UserPrefs]-qualified `DataStore<Preferences>` so the binary
 * footprint is one file (`becalm_user_prefs.preferences_pb`) rather than spawning a
 * third file just for counters — the plan explicitly forbids creating a new DataStore
 * instance to avoid file-collision risk during sign-out wipe.
 *
 * ## Key isolation
 * The [METRIC_SUBJECT_ONLY_SKIPPED_KEY] string is unique inside the shared file; no
 * existing [UserPrefsStore] key uses the `email_` prefix. [UserPrefsStore.clearAll]
 * will reset this counter to its default (0) on sign-out, which is the desired behaviour
 * — per-user metric accumulation should not survive across user sessions.
 */
public class MetricsStoreImpl @Inject constructor(
    @UserPrefs private val dataStore: DataStore<Preferences>,
) : MetricsStore {

    private val subjectOnlySkippedKey = METRIC_SUBJECT_ONLY_SKIPPED_KEY

    override suspend fun incrementSubjectOnlySkipped() {
        dataStore.edit { prefs ->
            prefs[subjectOnlySkippedKey] = (prefs[subjectOnlySkippedKey] ?: 0) + 1
        }
    }

    override fun observeSubjectOnlySkipped(): Flow<Int> =
        dataStore.data.map { it[subjectOnlySkippedKey] ?: 0 }
}

/**
 * DataStore preferences key for the `email_subject_only_skipped` counter.
 *
 * Hoisted to a file-private top-level so the literal `"email_subject_only_skipped"`
 * lives in exactly one place — the grep invariant in the plan asserts at least 2
 * matches across `main/`, satisfied by (1) this declaration and (2) any future
 * caller that constructs the same key (none today).
 */
private val METRIC_SUBJECT_ONLY_SKIPPED_KEY = intPreferencesKey("email_subject_only_skipped")
