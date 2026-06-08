package com.becalm.android.data.repository

internal const val SOURCE_CONNECTION_STATUS_NEEDS_REAUTH: String = "needs_reauth"

private val SYNCABLE_BACKEND_SOURCE_CONNECTION_STATUSES = setOf(
    "connected",
    "syncing",
    "synced",
    "failed",
)

internal fun isSyncableBackendSourceConnectionStatus(status: String): Boolean =
    status in SYNCABLE_BACKEND_SOURCE_CONNECTION_STATUSES
