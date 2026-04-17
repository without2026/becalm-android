package com.becalm.android.data.remote.interceptor

import javax.inject.Inject

/**
 * Generates opaque idempotency keys attached by [IdempotencyInterceptor] to
 * requests that carry the `X-BeCalm-Idempotent: 1` opt-in header.
 *
 * Decoupled from the interceptor so that tests can supply deterministic keys.
 */
public interface IdempotencyKeyProvider {

    /**
     * Generates a new idempotency key string.
     *
     * Each invocation must return a value that is unique across all concurrent and
     * historical calls from this device. The default implementation returns a random
     * UUID v4. Tests may override this to return fixed strings.
     */
    public fun generate(): String
}

/**
 * Production [IdempotencyKeyProvider] backed by [java.util.UUID.randomUUID].
 *
 * Each call to [generate] returns a fresh RFC-4122 UUID v4 in lower-case hyphenated form,
 * e.g. `"550e8400-e29b-41d4-a716-446655440000"`.
 *
 * Annotated with `@Inject` so Hilt can construct it without a `@Provides` binding.
 * SP-06 binds [IdempotencyKeyProvider] → [DefaultIdempotencyKeyProvider] in the network module.
 */
public class DefaultIdempotencyKeyProvider @Inject constructor() : IdempotencyKeyProvider {

    override fun generate(): String = java.util.UUID.randomUUID().toString()
}
