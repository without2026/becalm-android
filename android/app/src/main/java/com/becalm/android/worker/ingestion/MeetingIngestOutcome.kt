package com.becalm.android.worker.ingestion

public sealed interface MeetingIngestOutcome {
    public data class Success(val insertedCount: Int) : MeetingIngestOutcome
    public data object ScanFailed : MeetingIngestOutcome
}
