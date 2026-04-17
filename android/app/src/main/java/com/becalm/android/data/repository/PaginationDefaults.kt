package com.becalm.android.data.repository

/**
 * Shared pagination defaults for repositories that drive server-paged refresh loops.
 *
 * [REFRESH_PAGE_CAP] bounds the number of pages a single refresh call will walk before
 * returning control to the caller with `hasMore = true`. Both [CommitmentRepositoryImpl]
 * and [CalendarEventRepositoryImpl] previously declared this ceiling as a file-local
 * `private const val MAX_PAGES = 5`; the value is centralised here so the two call sites
 * stay aligned and the invariant is documented once.
 *
 * Semantics:
 * - Callers iterate up to `REFRESH_PAGE_CAP` pages per invocation.
 * - When the server keeps reporting `has_more = true` after the last page, the caller
 *   surfaces `hasMore = true` in `RefreshStats` so the UI or a follow-up worker can
 *   resume from the persisted cursor.
 * - Changing this cap changes the maximum server round-trips per refresh call and the
 *   maximum rows persisted in a single refresh; adjust with care.
 */
internal const val REFRESH_PAGE_CAP: Int = 5
