package com.becalm.android.data.local.db

import android.content.Context
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

private const val TAG = "BeCalmDatabaseProvider"

/**
 * Application-scoped holder for the **user-scoped** [BeCalmDatabase] instance.
 *
 * ## Why this exists
 * Pre-S6-A code opened a single `becalm.db` file for the whole device (see
 * [BeCalmDatabase.LEGACY_DATABASE_NAME]). The PIPA invariant that routine sign-out
 * must preserve Room data — implemented by [com.becalm.android.data.repository.AuthRepository.invalidateSession]
 * — therefore created a cross-account leakage surface: the next user to sign in on
 * the same device loaded the previous user's commitments, voice events, and
 * person-enrichment rows. [BeCalmDatabaseProvider] closes that surface by keying the
 * on-disk SQLite file on [BeCalmDatabase.deriveUserIdHash] of the current Supabase
 * userId, so two users on the same device physically cannot share a file.
 *
 * ## Bootstrap contract
 * [current] lazily opens the database on first access by reading
 * [UserPrefsStore.observeCurrentUserId]. If no user is signed in the call throws —
 * the DAO layer must never be touched before sign-in. On cold start
 * [com.becalm.android.BecalmApplication] nudges the provider via [ensureOpenFor]
 * so Hilt's first DAO injection resolves the correct user immediately.
 *
 * ## In-process user swap caveat (alpha MVP)
 * [ensureOpenFor] idempotently no-ops when the requested [userIdHash] matches the
 * already-open file. When the hash **differs**, the previous file is closed and a new
 * one is built, but `@Singleton` repositories that already captured a DAO reference
 * during the prior user's session continue to point at the closed database. For alpha
 * this is acceptable because [com.becalm.android.data.repository.AuthRepositoryImpl.signOut]
 * / [com.becalm.android.data.repository.AuthRepositoryImpl.invalidateSession] invoke
 * [close] and the recommended UX is a cold restart before the next sign-in. A
 * follow-up refactor will migrate repositories to `Provider<Dao>` injection so
 * in-process swap becomes safe; tracked in `docs/plans/db-auth-user-scoped-database.md`
 * §Appendix.
 *
 * ## Concurrency
 * Every state transition (open / swap / close / lazy bootstrap) is serialized behind
 * [lock] so concurrent Hilt injections and worker invocations observe a consistent
 * instance.
 */
@Singleton
public class BeCalmDatabaseProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) {

    private val lock: ReentrantLock = ReentrantLock()

    // Guarded by [lock]. `null` means no database is currently open.
    private var currentDb: BeCalmDatabase? = null
    private var currentHash: String? = null
    private var legacyCleanupDone: Boolean = false

    /**
     * Ensures the provider is open against [userIdHash]. Idempotent when the same hash
     * is already open.
     *
     * When [userIdHash] differs from the currently-open hash this method closes the
     * prior instance and builds a fresh one. See the in-process swap caveat in the
     * class-level KDoc — callers that rely on `@Singleton`-cached DAO references
     * must restart the process after invoking this path.
     *
     * @param userIdHash Result of [BeCalmDatabase.deriveUserIdHash] for the signed-in user.
     */
    public fun ensureOpenFor(userIdHash: String) {
        lock.withLock {
            if (currentHash == userIdHash && currentDb != null) {
                return
            }
            if (currentDb != null) {
                logger.w(
                    TAG,
                    "user-scope swap detected (${currentHash?.take(4)}… → ${userIdHash.take(4)}…); " +
                        "caller must restart the process before reusing cached DAOs",
                )
                currentDb?.close()
            }
            cleanupLegacyFileIfPresent()
            val db = BeCalmDatabase.build(context, userIdHash)
            currentDb = db
            currentHash = userIdHash
            logger.i(TAG, "opened user-scoped database (hash=${userIdHash.take(4)}…)")
        }
    }

    /**
     * Returns the currently-open database, lazily opening it from the persisted
     * current userId on first access.
     *
     * @throws IllegalStateException when no user is signed in — callers must never
     *   touch Room before [com.becalm.android.data.repository.AuthRepository] has
     *   finished a successful sign-in.
     */
    public fun current(): BeCalmDatabase {
        lock.withLock {
            currentDb?.let { return it }
            val userId = runBlocking { userPrefsStore.observeCurrentUserId().first() }
                ?: error(
                    "BeCalmDatabase accessed before sign-in. Room requires an authenticated " +
                        "user; defer DAO usage until AuthRepository.observeAuthState() emits " +
                        "AuthState.Authenticated.",
                )
            val hash = BeCalmDatabase.deriveUserIdHash(userId)
            cleanupLegacyFileIfPresent()
            val db = BeCalmDatabase.build(context, hash)
            currentDb = db
            currentHash = hash
            logger.i(TAG, "lazy-opened user-scoped database (hash=${hash.take(4)}…)")
            return db
        }
    }

    /**
     * Returns the id-hash of the user whose database is currently open, or `null`
     * when no database is open.
     *
     * Consumed by [com.becalm.android.data.repository.AuthRepositoryImpl] to detect
     * in-process account swaps at sign-in time (a non-null prior hash that differs
     * from the newly-signed-in user's hash triggers a forced process restart so the
     * `@Singleton` DAO graph cannot carry the prior user's references across the
     * swap, AUTH-008).
     */
    public fun currentUserIdHash(): String? = lock.withLock { currentHash }

    /**
     * Closes the currently-open database (if any) and clears the provider's hash.
     *
     * Called from the sign-out step lists in
     * [com.becalm.android.data.repository.AuthRepositoryImpl] so that the SQLite WAL
     * is flushed and the next sign-in can open a fresh per-user file without stale
     * filesystem locks.
     */
    public fun close() {
        lock.withLock {
            currentDb?.close()
            currentDb = null
            currentHash = null
            logger.i(TAG, "closed user-scoped database")
        }
    }

    /**
     * One-time deletion of the pre-user-scoping `becalm.db` residue on devices that
     * upgraded from an alpha build. Idempotent — subsequent calls are cheap no-ops.
     *
     * Alpha users have not been migrated to the new per-user file because the single
     * legacy file already co-mingled multiple sessions in the same SQLite — preserving
     * it post-S6-A would re-open the cross-account leakage surface this plan closes.
     */
    private fun cleanupLegacyFileIfPresent() {
        if (legacyCleanupDone) return
        val legacyFile = context.getDatabasePath(BeCalmDatabase.LEGACY_DATABASE_NAME)
        if (legacyFile.exists()) {
            val deleted = context.deleteDatabase(BeCalmDatabase.LEGACY_DATABASE_NAME)
            logger.i(TAG, "legacy becalm.db cleanup: deleted=$deleted")
        }
        legacyCleanupDone = true
    }
}
