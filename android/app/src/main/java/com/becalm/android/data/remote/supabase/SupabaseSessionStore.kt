package com.becalm.android.data.remote.supabase

/**
 * Persistence contract for a [SupabaseSession].
 *
 * AUTH-004 invariant: tokens are **always** stored encrypted via the Android Keystore.
 * The concrete implementation `EncryptedTokenStore` (owned by SP-15) fulfills this contract
 * using `EncryptedSharedPreferences`. No plaintext storage of tokens is permitted anywhere
 * in the codebase.
 *
 * This interface is intentionally minimal — callers only need to save, load, and clear.
 * Encryption, key management, and migration are entirely encapsulated by the SP-15 impl.
 *
 * Lifecycle note: [clear] is called by `AuthRepository` (SP-16) as part of the full sign-out
 * wipe sequence. Callers of [SupabaseAuthClient.signOut] must NOT call [clear] directly;
 * SP-16 orchestrates the wipe of this store alongside Room and DataStore.
 */
public interface SupabaseSessionStore {

    /**
     * Persists [session] to encrypted storage, replacing any previously saved session.
     *
     * @param session The session to persist. Must not be called with a partially-constructed
     *   session — all fields must be valid before saving.
     */
    public suspend fun save(session: SupabaseSession)

    /**
     * Loads the most recently persisted [SupabaseSession], or `null` if no session exists.
     *
     * Returns `null` on first launch, after [clear], or if the stored data is corrupt/unreadable.
     */
    public suspend fun load(): SupabaseSession?

    /**
     * Deletes the persisted session from encrypted storage.
     *
     * Called by SP-16 `AuthRepository` during the sign-out wipe sequence.
     */
    public suspend fun clear()
}
