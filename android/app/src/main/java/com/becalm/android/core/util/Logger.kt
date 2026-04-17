package com.becalm.android.core.util

import timber.log.Timber

/**
 * Thin abstraction over a logging back-end.
 *
 * Callers depend on this interface rather than a concrete logger so that unit tests can
 * supply a no-op or recording implementation without touching global [Timber] state.
 */
public interface Logger {
    public fun v(tag: String, message: String, throwable: Throwable? = null)
    public fun d(tag: String, message: String, throwable: Throwable? = null)
    public fun i(tag: String, message: String, throwable: Throwable? = null)
    public fun w(tag: String, message: String, throwable: Throwable? = null)
    public fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Production [Logger] backed by [Timber].
 *
 * Note: [Timber.plant] is intentionally not called here. The Application class (Round 10)
 * is responsible for planting the appropriate tree before any logging occurs.
 */
public object TimberLogger : Logger {
    override fun v(tag: String, message: String, throwable: Throwable?) = Timber.tag(tag).v(throwable, message)
    override fun d(tag: String, message: String, throwable: Throwable?) = Timber.tag(tag).d(throwable, message)
    override fun i(tag: String, message: String, throwable: Throwable?) = Timber.tag(tag).i(throwable, message)
    override fun w(tag: String, message: String, throwable: Throwable?) = Timber.tag(tag).w(throwable, message)
    override fun e(tag: String, message: String, throwable: Throwable?) = Timber.tag(tag).e(throwable, message)
}
