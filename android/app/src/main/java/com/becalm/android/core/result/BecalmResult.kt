package com.becalm.android.core.result

/**
 * A discriminated union representing either a successful value [T] or a [BecalmError].
 *
 * Because this is a sealed interface, `when` expressions over it are exhaustive — the compiler
 * enforces handling of both [Success] and [Failure] without a trailing `else` branch.
 */
public sealed interface BecalmResult<out T> {

    /** Carries the successful result [value]. */
    public data class Success<T>(val value: T) : BecalmResult<T>

    /** Carries a typed [error] describing the failure. */
    public data class Failure(val error: BecalmError) : BecalmResult<Nothing>
}

/**
 * Returns the contained value when this is [BecalmResult.Success], or `null` for [BecalmResult.Failure].
 */
public fun <T> BecalmResult<T>.getOrNull(): T? = when (this) {
    is BecalmResult.Success -> value
    is BecalmResult.Failure -> null
}

/**
 * Returns the contained [BecalmError] when this is [BecalmResult.Failure], or `null` for [BecalmResult.Success].
 */
public fun <T> BecalmResult<T>.errorOrNull(): BecalmError? = when (this) {
    is BecalmResult.Success -> null
    is BecalmResult.Failure -> error
}

/**
 * Transforms a [BecalmResult.Success] value using [transform], propagating [BecalmResult.Failure] unchanged.
 */
public inline fun <T, R> BecalmResult<T>.map(transform: (T) -> R): BecalmResult<R> = when (this) {
    is BecalmResult.Success -> BecalmResult.Success(transform(value))
    is BecalmResult.Failure -> this
}

/**
 * Chains a computation that itself returns a [BecalmResult], short-circuiting on [BecalmResult.Failure].
 */
public inline fun <T, R> BecalmResult<T>.flatMap(transform: (T) -> BecalmResult<R>): BecalmResult<R> =
    when (this) {
        is BecalmResult.Success -> transform(value)
        is BecalmResult.Failure -> this
    }

/**
 * Executes [block] as a side effect when this is [BecalmResult.Success], then returns the receiver unchanged.
 */
public inline fun <T> BecalmResult<T>.onSuccess(block: (T) -> Unit): BecalmResult<T> {
    if (this is BecalmResult.Success) block(value)
    return this
}

/**
 * Executes [block] as a side effect when this is [BecalmResult.Failure], then returns the receiver unchanged.
 */
public inline fun <T> BecalmResult<T>.onFailure(block: (BecalmError) -> Unit): BecalmResult<T> {
    if (this is BecalmResult.Failure) block(error)
    return this
}

/**
 * Returns the success value, or falls back to the result of [default] when this is [BecalmResult.Failure].
 */
public inline fun <T> BecalmResult<T>.getOrElse(default: (BecalmError) -> T): T = when (this) {
    is BecalmResult.Success -> value
    is BecalmResult.Failure -> default(error)
}
