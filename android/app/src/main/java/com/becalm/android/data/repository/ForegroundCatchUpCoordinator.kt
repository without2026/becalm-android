package com.becalm.android.data.repository

import androidx.work.WorkManager
import com.becalm.android.workers.UploadWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

// spec: ING-011 — PRIMARY 100%-arrival path
// ON_START lifecycle entry or pull-to-refresh triggers 6-source parallel catch-up.
// Each adapter is independent — one failure does NOT block others.
// After all adapters complete, SYNC-006 UploadWorker is enqueued.

data class CatchUpResult(
    val sourceType: String,
    val newEventsCount: Int,
    val success: Boolean,
    val errorMessage: String? = null
)

@Singleton
class ForegroundCatchUpCoordinator @Inject constructor(
    private val voiceIngestionRepository: VoiceIngestionRepository,
    // Email + calendar adapters injected here (SP-4 implementations)
    // For scaffold: voice adapter is wired; others are stubs
    private val workManager: WorkManager
) {
    // spec: ING-011 — 6 source adapters run in parallel coroutines
    // spec: ING-011 — each adapter independently succeeds or fails (no cross-contamination)
    suspend fun performCatchUp(): List<CatchUpResult> = coroutineScope {
        val results = mutableListOf<CatchUpResult>()

        // Voice adapter (ING-001 + ING-012)
        val voiceDeferred = async {
            runCatching {
                val events = voiceIngestionRepository.performForegroundCatchUp()
                CatchUpResult("voice", events.size, true)
            }.getOrElse { e ->
                CatchUpResult("voice", 0, false, e.message)
            }
        }

        // spec: ING-011 — Gmail adapter (SP-4 stub — full impl in SP-4)
        val gmailDeferred = async {
            CatchUpResult("gmail", 0, true) // SP-4 will implement GmailAdapter
        }

        // spec: ING-011 — Outlook Mail adapter (SP-4 stub)
        val outlookMailDeferred = async {
            CatchUpResult("outlook_mail", 0, true) // SP-4 will implement OutlookMailAdapter
        }

        // spec: ING-011 — Naver IMAP adapter (SP-4 stub)
        val naverImapDeferred = async {
            CatchUpResult("naver_imap", 0, true) // SP-4 will implement NaverImapAdapter
        }

        // spec: ING-011 — Google Calendar adapter (SP-4 stub)
        val googleCalendarDeferred = async {
            CatchUpResult("google_calendar", 0, true) // SP-4 will implement GoogleCalendarAdapter
        }

        // spec: ING-011 — Outlook Calendar adapter (SP-4 stub)
        val outlookCalendarDeferred = async {
            CatchUpResult("outlook_calendar", 0, true) // SP-4 will implement OutlookCalendarAdapter
        }

        results.add(voiceDeferred.await())
        results.add(gmailDeferred.await())
        results.add(outlookMailDeferred.await())
        results.add(naverImapDeferred.await())
        results.add(googleCalendarDeferred.await())
        results.add(outlookCalendarDeferred.await())

        // spec: SYNC-006 — after all adapters complete, immediately enqueue upload
        UploadWorker.enqueue(workManager)

        results
    }
}
