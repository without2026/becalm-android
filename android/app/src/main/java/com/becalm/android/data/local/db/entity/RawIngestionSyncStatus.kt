package com.becalm.android.data.local.db.entity

public object RawIngestionSyncStatus {
    public const val PENDING: String = "pending"
    public const val SYNCED: String = "synced"
    public const val FAILED: String = "failed"
    public const val AWAITING_CONSENT: String = "awaiting_consent"
    public const val DETECTED_PENDING_CONFIRMATION: String = "detected_pending_confirmation"
    public const val SKIPPED_BY_USER: String = "skipped_by_user"
}
