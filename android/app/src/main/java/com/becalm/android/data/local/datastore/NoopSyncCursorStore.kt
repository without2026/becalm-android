package com.becalm.android.data.local.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal object NoopSyncCursorStore : SyncCursorStore {
    override fun observeCursor(source: String): Flow<String?> = flowOf(null)
    override suspend fun setCursor(source: String, cursor: String?) = Unit
    override suspend fun clearCursor(source: String) = Unit
    override suspend fun clearAll() = Unit
    override fun observeGmailHistoryId(): Flow<Long?> = flowOf(null)
    override suspend fun setGmailHistoryId(historyId: Long?) = Unit
    override fun observeImapState(mailbox: String): Flow<ImapCursorState?> = flowOf(null)
    override suspend fun setImapState(mailbox: String, state: ImapCursorState?) = Unit
    override fun observeMediaStoreLastSeen(kind: String): Flow<Long?> = flowOf(null)
    override suspend fun setMediaStoreLastSeen(kind: String, epochMs: Long?) = Unit
    override suspend fun runOutlookMailCursorMigrationV2() = Unit
    override suspend fun runImapCursorMigrationV2() = Unit
}
