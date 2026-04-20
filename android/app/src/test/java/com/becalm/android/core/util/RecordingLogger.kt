package com.becalm.android.core.util

/**
 * Test double for [Logger] that records every call to an in-memory list for later assertion.
 *
 * Thread-safe via `synchronized` on [entries] — individual unit tests should not rely on
 * concurrent ordering, but multi-dispatcher tests using `runTest` can still assert content.
 *
 * Usage:
 * ```
 * val logger = RecordingLogger()
 * vm.doWork()
 * assertTrue(logger.entries.any { it.level == Level.E && "upload failed" in it.message })
 * ```
 */
public class RecordingLogger : Logger {

    public enum class Level { D, I, W, E }

    public data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private val _entries: MutableList<Entry> = mutableListOf()

    /** Immutable snapshot of every call made so far, in insertion order. */
    public val entries: List<Entry>
        get() = synchronized(_entries) { _entries.toList() }

    /** Remove all recorded entries. Useful between phases of a single test. */
    public fun clear() {
        synchronized(_entries) { _entries.clear() }
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        record(Level.D, tag, message, throwable)
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        record(Level.I, tag, message, throwable)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        record(Level.W, tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        record(Level.E, tag, message, throwable)
    }

    private fun record(level: Level, tag: String, message: String, throwable: Throwable?) {
        synchronized(_entries) {
            _entries.add(Entry(level, tag, message, throwable))
        }
    }
}
