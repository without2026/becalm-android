package com.becalm.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
public class DebugEmailExtractionRepairReceiver : BroadcastReceiver() {
    @Inject lateinit var userPrefsStore: UserPrefsStore
    @Inject lateinit var databaseProvider: BeCalmDatabaseProvider
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPAIR_HTML_ONLY_EMAIL_EXTRACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val userId = userPrefsStore.observeCurrentUserId().first()?.takeIf { it.isNotBlank() }
                    ?: error("No active userId")
                databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
                val db = databaseProvider.current().openHelper.writableDatabase
                val count = db.query(COUNT_SQL, arrayOf(userId)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                db.execSQL(REPAIR_SQL, arrayOf(userId))
                if (count > 0) {
                    workScheduler.enqueueUpload()
                }
                Timber.i("Debug email extraction repair marked count=$count")
            }.onFailure { error ->
                Timber.e(error, "Debug email extraction repair failed")
            }
            pending.finish()
        }
    }

    public companion object {
        public const val ACTION_REPAIR_HTML_ONLY_EMAIL_EXTRACTION: String =
            "com.becalm.android.DEBUG_REPAIR_HTML_ONLY_EMAIL_EXTRACTION"

        private val MAIL_SOURCES = "('gmail','outlook_mail','naver_imap','daum_imap')"

        private val BASE_WHERE = """
            rie.user_id = ?
              AND rie.source_type IN $MAIL_SOURCES
              AND rie.sync_status = 'synced'
              AND EXISTS (
                SELECT 1 FROM email_body eb
                WHERE eb.raw_event_id = rie.id
                  AND eb.parse_failed = 0
                  AND eb.group_email = 0
                  AND (eb.body_plain IS NULL OR TRIM(eb.body_plain) = '')
                  AND eb.body_html IS NOT NULL
                  AND TRIM(eb.body_html) != ''
              )
              AND NOT EXISTS (
                SELECT 1 FROM commitments c
                WHERE c.user_id = rie.user_id
                  AND c.source_type = rie.source_type
                  AND c.source_ref = rie.source_ref
              )
        """.trimIndent()

        private val COUNT_SQL = """
            SELECT COUNT(*)
            FROM raw_ingestion_events rie
            WHERE $BASE_WHERE
        """.trimIndent()

        private val REPAIR_SQL = """
            UPDATE raw_ingestion_events AS rie
            SET sync_status = 'pending',
                last_error = 'debug_html_body_repair'
            WHERE $BASE_WHERE
        """.trimIndent()
    }
}
