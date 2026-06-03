package com.becalm.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.becalm.android.BuildConfig
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.entity.CommitmentDecisionStatus
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.repository.ProcessingStatusMessages
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceMirrorCursorReset
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import timber.log.Timber

@AndroidEntryPoint
public class DebugPersonRenderingSeedReceiver : BroadcastReceiver() {
    @Inject lateinit var userPrefsStore: UserPrefsStore
    @Inject lateinit var sessionStore: SupabaseSessionStore
    @Inject lateinit var databaseProvider: BeCalmDatabaseProvider
    @Inject lateinit var processingStatusRepository: ProcessingStatusRepository
    @Inject lateinit var sourceStatusRepository: SourceStatusRepository
    @Inject lateinit var syncCursorStore: SyncCursorStore
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                ACTION,
                ACTION_SEED_SCALE_PERSON_TIMELINE,
                ACTION_PREPARE_STAGING_MIRROR_SMOKE,
                ACTION_REPORT_STAGING_MIRROR_SMOKE,
                ACTION_ENQUEUE_CLOVA_AUDIO_E2E,
                ACTION_SEED_MEETING_SPEAKER_JOURNEY,
                ACTION_SEED_SYNC_FAILURE_UI_SMOKE,
                ACTION_SEED_PROCESSING_STATUS_SMOKE,
                ACTION_SEED_ACCOUNT_SWAP_SMOKE,
                ACTION_SEED_PRIVACY_SMOKE,
                ACTION_SEED_CORRECTION_SMOKE,
                ACTION_REPORT_CORRECTION_SMOKE,
            )
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                when (intent.action) {
                    ACTION -> seedDemoData()
                    ACTION_SEED_SCALE_PERSON_TIMELINE -> seedScalePersonTimeline()
                    ACTION_PREPARE_STAGING_MIRROR_SMOKE -> prepareStagingMirrorSmoke(intent)
                    ACTION_REPORT_STAGING_MIRROR_SMOKE -> reportStagingMirrorSmoke(intent)
                    ACTION_ENQUEUE_CLOVA_AUDIO_E2E -> enqueueClovaAudioE2e(intent)
                    ACTION_SEED_MEETING_SPEAKER_JOURNEY -> seedMeetingSpeakerJourney()
                    ACTION_SEED_SYNC_FAILURE_UI_SMOKE -> seedSyncFailureUiSmoke()
                    ACTION_SEED_PROCESSING_STATUS_SMOKE -> seedProcessingStatusSmoke()
                    ACTION_SEED_ACCOUNT_SWAP_SMOKE -> seedAccountSwapSmoke()
                    ACTION_SEED_PRIVACY_SMOKE -> seedPrivacySmoke()
                    ACTION_SEED_CORRECTION_SMOKE -> seedCorrectionSmoke(intent)
                    ACTION_REPORT_CORRECTION_SMOKE -> reportCorrectionSmoke(intent)
                }
            }.onSuccess {
                Timber.i("Debug action completed action=${intent.action}")
            }.onFailure { error ->
                Timber.e(error, "Debug action failed action=${intent.action}")
            }
            pending.finish()
        }
    }

    private suspend fun enqueueClovaAudioE2e(intent: Intent) {
        val userId = ensureE2eSession(intent)
        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)?.takeIf { it.isNotBlank() }
            ?: error("Missing $EXTRA_AUDIO_PATH")
        val audioFile = File(audioPath)
        require(audioFile.exists()) { "Audio file does not exist: $audioPath" }
        val durationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0)
        require(durationSeconds > 0) { "$EXTRA_DURATION_SECONDS must be positive" }
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE)?.takeIf { it.isNotBlank() }
            ?: SourceType.MEETING
        require(sourceType in setOf(SourceType.MEETING, SourceType.VOICE, SourceType.CALL_RECORDING)) {
            "Unsupported audio source_type for Clova E2E: $sourceType"
        }

        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val title = intent.getStringExtra(EXTRA_EVENT_TITLE)?.takeIf { it.isNotBlank() }
            ?: audioFile.name
        val sourceRef = Uri.fromFile(audioFile).toString()
        val rawEventId = UUID.randomUUID().toString()

        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        userPrefsStore.setThirdPartyProvisionConsent(true)
        userPrefsStore.setSourceEnabled(sourceType, true)
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
        val db = databaseProvider.current()

        val entity = RawIngestionEventEntity(
            id = rawEventId,
            userId = userId,
            clientEventId = UUID.nameUUIDFromBytes(
                "debug-clova-e2e:$sourceType:$sourceRef:${now.toEpochMilliseconds()}".toByteArray(),
            ).toString(),
            sourceType = sourceType,
            sourceRef = sourceRef,
            counterpartyRef = intent.getStringExtra(EXTRA_COUNTERPARTY_REF)?.takeIf { it.isNotBlank() },
            eventTitle = title,
            eventSnippet = null,
            durationSeconds = durationSeconds,
            timestamp = now,
            syncStatus = if (sourceType in setOf(SourceType.MEETING, SourceType.CALL_RECORDING)) {
                MeetingSpeakerPreviewStatus.PENDING
            } else {
                "pending"
            },
        )
        val inserted = db.rawIngestionEventDao().insert(entity)
        require(inserted != -1L) { "Raw event insert ignored for clientEventId=${entity.clientEventId}" }
        if (sourceType in setOf(SourceType.MEETING, SourceType.CALL_RECORDING)) {
            workScheduler.enqueueMeetingSpeakerPreview(rawEventId = rawEventId, audioUri = sourceRef)
        } else {
            workScheduler.enqueueVoiceUpload(rawEventId = rawEventId, audioUri = sourceRef)
        }
        Timber.i(
            "Debug Clova audio E2E enqueued rawEventId=$rawEventId " +
                "sourceType=$sourceType durationSeconds=$durationSeconds sourceRef=$sourceRef",
        )
    }

    private suspend fun ensureE2eSession(intent: Intent): String {
        val existingUserId = userPrefsStore.observeCurrentUserId().first()?.takeIf { it.isNotBlank() }
        val providedUserId = intent.getStringExtra(EXTRA_USER_ID)?.takeIf { it.isNotBlank() }
        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }
        if (accessToken != null && providedUserId != null) {
            val expiresAtMillis = intent.getLongExtra(
                EXTRA_EXPIRES_AT_EPOCH_MS,
                System.currentTimeMillis() + 60L * 60L * 1000L,
            )
            userPrefsStore.setCurrentUserId(providedUserId)
            sessionStore.save(
                SupabaseSession(
                    accessToken = accessToken,
                    refreshToken = intent.getStringExtra(EXTRA_REFRESH_TOKEN).orEmpty(),
                    userId = providedUserId,
                    email = intent.getStringExtra(EXTRA_EMAIL)?.takeIf { it.isNotBlank() }
                        ?: "adb-clova-e2e@becalm.local",
                    expiresAt = Instant.fromEpochMilliseconds(expiresAtMillis),
                ),
            )
            return providedUserId
        }
        return existingUserId
            ?: error("No active userId. Sign in or provide $EXTRA_USER_ID + $EXTRA_ACCESS_TOKEN before running Clova audio E2E.")
    }

    private suspend fun seedDemoData() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(true)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(USER_ID),
                refreshToken = "debug-refresh-token",
                userId = USER_ID,
                email = "debug.person.rendering@becalm.local",
                expiresAt = expiresAt,
            ),
        )

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        val db = databaseProvider.current()
        clearDemoRows(db)
        seedConnectedSources(now)

        val kimHyunsooSmsId = "qa-raw-img-8260"
        val kimYoungkyungIntroId = "qa-raw-img-8261"
        val kimYoungkyungScheduleId = "qa-raw-img-8262"
        val startupReplyId = "qa-raw-img-8263"
        val startupInquiryId = "qa-raw-img-8264"
        val parkJinkyuId = "qa-raw-img-8265"
        val relationArchitectureId = "qa-raw-img-8270"
        val localFirstMemoryId = "qa-raw-img-8280"
        val historyWindowId = "qa-raw-img-8284"
        val oldEmailImportId = "qa-raw-img-8287"

        db.rawIngestionEventDao().upsertSyncedFromServer(
            listOf(
                rawEvent(
                    id = kimHyunsooSmsId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8260.png",
                    counterpartyRef = "김현수",
                    title = "문자 캡처 - 김현수 팀장님",
                    snippet = "약속 장소 도착과 도착 예정 시간을 확인한 대화입니다.",
                    timestamp = now.minusHours(10),
                    extractedCount = 0,
                ),
                rawEvent(
                    id = kimYoungkyungIntroId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8261.png",
                    counterpartyRef = "김영경",
                    title = "카카오톡 캡처 - 김영경 센터장님 소개",
                    snippet = "WITHOUT 현황 설명과 피드백 미팅을 요청한 대화입니다.",
                    timestamp = now.minusHours(9),
                    extractedCount = 2,
                ),
                rawEvent(
                    id = kimYoungkyungScheduleId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8262.png",
                    counterpartyRef = "김영경",
                    title = "카카오톡 캡처 - 김영경 센터장님 일정 조율",
                    snippet = "다음 주 화요일 오후 미팅과 정확한 시간 재연락이 잡힌 대화입니다.",
                    timestamp = now.minusHours(8),
                    extractedCount = 2,
                ),
                rawEvent(
                    id = startupReplyId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8263.png",
                    counterpartyRef = "김채린",
                    title = "메일 캡처 - 스타트업 익스프레스 답변",
                    snippet = "센터장님들께 직접 연락해 멘토링 일정을 잡으라는 안내입니다.",
                    timestamp = now.minusHours(7),
                    extractedCount = 1,
                    folder = "INBOX",
                ),
                rawEvent(
                    id = startupInquiryId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8264.png",
                    counterpartyRef = "스타트업 연구원",
                    title = "메일 캡처 - 스타트업 익스프레스 문의",
                    snippet = "행사 참석 가능 여부와 센터장 미팅 가능성을 문의한 보낸 메일입니다.",
                    timestamp = now.minusHours(6),
                    extractedCount = 1,
                    folder = "SENT",
                ),
                rawEvent(
                    id = parkJinkyuId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8265.png",
                    counterpartyRef = "박진규",
                    title = "문자 캡처 - 박진규 센터장님 미팅 요청",
                    snippet = "사업 현황 설명과 피드백 미팅 가능 시간을 요청한 대화입니다.",
                    timestamp = now.minusHours(5),
                    extractedCount = 1,
                ),
                rawEvent(
                    id = relationArchitectureId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8270.png",
                    counterpartyRef = "Jihoon Kang",
                    title = "메신저 캡처 - 관계 지능 UI 결정",
                    snippet = "미래 일정만 UI에 노출하고 과거 맥락은 사람 중심으로 정리하는 방향을 논의했습니다.",
                    timestamp = now.minusHours(4),
                    extractedCount = 1,
                ),
                rawEvent(
                    id = localFirstMemoryId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8280.png",
                    counterpartyRef = "Jihoon Kang",
                    title = "메신저 캡처 - local-first와 memory.md",
                    snippet = "원문은 로컬에 두고 memory.md와 relation intelligence를 분리해 관리하는 방향에 동의했습니다.",
                    timestamp = now.minusHours(3),
                    extractedCount = 1,
                ),
                rawEvent(
                    id = historyWindowId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8284.png",
                    counterpartyRef = "Jihoon Kang",
                    title = "메신저 캡처 - interaction history 범위",
                    snippet = "사람별 과거 interaction history를 3개월까지 늘리는 방향을 승인했습니다.",
                    timestamp = now.minusHours(2),
                    extractedCount = 2,
                ),
                rawEvent(
                    id = oldEmailImportId,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "qa-message-screenshot:IMG_8287.png",
                    counterpartyRef = "Jihoon Kang",
                    title = "메신저 캡처 - 이전 이메일 동기화",
                    snippet = "이전 이메일을 가져와 사람 중심 interaction을 채우는 방향을 논의했습니다.",
                    timestamp = now.minusHours(1),
                    extractedCount = 1,
                ),
            ),
        )

        db.personIndexDao().upsertPersons(
            listOf(
                person(PERSON_KIM_HYUNSOO, "김현수", null, null, now),
                person(PERSON_KIM_YOUNGKYUNG, "김영경", null, null, now),
                person(PERSON_KIM_CHAERIN, "김채린", null, null, now),
                person(PERSON_PARK_JINKYU, "박진규", null, null, now),
                person(PERSON_JIHOON, "Jihoon Kang", null, null, now),
                person(PERSON_KYE, "Kye Lim", null, null, now),
            ),
        )
        db.personIndexDao().upsertIdentities(
            listOf(
                identity(PERSON_KIM_HYUNSOO, "name", "김현수", "김현수", SourceType.MESSAGE_SCREENSHOT, now, true),
                identity(PERSON_KIM_YOUNGKYUNG, "name", "김영경", "김영경", SourceType.MESSAGE_SCREENSHOT, now, true),
                identity(PERSON_KIM_CHAERIN, "name", "김채린", "김채린", SourceType.MESSAGE_SCREENSHOT, now, true),
                identity(PERSON_PARK_JINKYU, "name", "박진규", "박진규", SourceType.MESSAGE_SCREENSHOT, now, true),
                identity(PERSON_JIHOON, "name", "jihoon kang", "Jihoon Kang", SourceType.MESSAGE_SCREENSHOT, now, true),
                identity(PERSON_KYE, "name", "kye lim", "Kye Lim", SourceType.MESSAGE_SCREENSHOT, now, true),
            ),
        )
        db.personEnrichmentDao().upsertAll(
            listOf(
                enrichment("김현수", "김현수", "팀장님", now),
                enrichment("김영경", "김영경", "센터장님", now),
                enrichment("김채린", "김채린", "담당자", now),
                enrichment("박진규", "박진규", "센터장님", now),
                enrichment("jihoon kang", "Jihoon Kang", null, now),
                enrichment("kye lim", "Kye Lim", null, now),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                participant(kimHyunsooSmsId, PERSON_KIM_HYUNSOO, "김현수", "counterparty", "counterparty", "김현수 팀장님", now.minusHours(10), "팀장님"),
                participant(kimYoungkyungIntroId, PERSON_KIM_YOUNGKYUNG, "김영경", "recipient", "counterparty", "김영경 센터장님", now.minusHours(9), "센터장님"),
                participant(kimYoungkyungScheduleId, PERSON_KIM_YOUNGKYUNG, "김영경", "counterparty", "counterparty", "김영경 센터장님", now.minusHours(8), "센터장님"),
                participant(startupReplyId, PERSON_KIM_CHAERIN, "김채린", "sender", "counterparty", "메일 발신자 김채린", now.minusHours(7), null),
                participant(startupReplyId, PERSON_KIM_YOUNGKYUNG, "김영경", "mentioned", "counterparty", "김영경 센터장님 연락처", now.minusHours(7), "센터장님"),
                participant(startupReplyId, PERSON_PARK_JINKYU, "박진규", "mentioned", "counterparty", "박진규 센터장님 연락처", now.minusHours(7), "센터장님"),
                participant(startupInquiryId, PERSON_KIM_YOUNGKYUNG, "김영경", "mentioned", "counterparty", "김영경 센터장님", now.minusHours(6), "센터장님"),
                participant(startupInquiryId, PERSON_PARK_JINKYU, "박진규", "mentioned", "counterparty", "박진규 센터장님", now.minusHours(6), "센터장님"),
                participant(parkJinkyuId, PERSON_PARK_JINKYU, "박진규", "recipient", "counterparty", "박진규 센터장님", now.minusHours(5), "센터장님"),
                participant(relationArchitectureId, PERSON_JIHOON, "Jihoon Kang", "counterparty", "counterparty", "Jihoon Kang", now.minusHours(4), null),
                participant(relationArchitectureId, PERSON_KYE, "Kye Lim", "speaker", "participant", "Kye Lim", now.minusHours(4), null),
                participant(localFirstMemoryId, PERSON_JIHOON, "Jihoon Kang", "speaker", "counterparty", "Jihoon Kang", now.minusHours(3), null),
                participant(localFirstMemoryId, PERSON_KYE, "Kye Lim", "speaker", "participant", "Kye Lim", now.minusHours(3), null),
                participant(historyWindowId, PERSON_JIHOON, "Jihoon Kang", "sender", "counterparty", "Jihoon Kang", now.minusHours(2), null),
                participant(historyWindowId, PERSON_KYE, "Kye Lim", "sender", "participant", "Kye Lim", now.minusHours(2), null),
                participant(oldEmailImportId, PERSON_JIHOON, "Jihoon Kang", "counterparty", "counterparty", "Jihoon Kang", now.minusHours(1), null),
                participant(oldEmailImportId, PERSON_KYE, "Kye Lim", "sender", "participant", "Kye Lim", now.minusHours(1), null),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment("qa-cmt-youngkyung-meeting-request", PERSON_KIM_YOUNGKYUNG, kimYoungkyungIntroId, CommitmentItemType.ACTION, "take", null, null, "김영경 센터장님과 현황 설명 및 피드백 미팅 조율", "잠시라도 시간을 내주신다면 저희의 현황을 설명드리고 센터장님의 날카로운 피드백을 듣고 싶습니다.", now.minusHours(9), null, null),
                commitment("qa-cmt-youngkyung-schedule", PERSON_KIM_YOUNGKYUNG, kimYoungkyungScheduleId, CommitmentItemType.SCHEDULE, null, CommitmentScheduleStatus.CONFIRMED, null, "다음 주 화요일 오후 김영경 센터장님 미팅", "다음주 화요일 오후시간 좋습니다", now.minusHours(8), Instant.parse("2026-05-12T14:00:00+09:00"), "다음주 화요일 오후시간"),
                commitment("qa-cmt-youngkyung-time-followup", PERSON_KIM_YOUNGKYUNG, kimYoungkyungScheduleId, CommitmentItemType.ACTION, "take", null, null, "김영경 센터장님이 정확한 시간을 다시 알려주기", "낮시간에 다시 톡드릴게요!", now.minusHours(8), null, "낮시간"),
                commitment("qa-cmt-startup-contact-centers", PERSON_KIM_CHAERIN, startupReplyId, CommitmentItemType.ACTION, "give", null, null, "센터장님들께 직접 연락해 멘토링 일자 잡기", "센터장님들께 직접 연락드려서 멘토링 일자를 잡으시면 될거같습니다!", now.minusHours(7), null, null),
                commitment("qa-cmt-park-jinkyu-availability", PERSON_PARK_JINKYU, parkJinkyuId, CommitmentItemType.ACTION, "take", null, null, "박진규 센터장님이 가능한 미팅 시간 알려주기", "시간/장소 모두 센터장님 가능하신 때에 맞추어 찾아뵐 수 있으니, 편하게 말씀주십시오.", now.minusHours(5), null, null),
                commitment("qa-cmt-relation-ui-decision", PERSON_JIHOON, relationArchitectureId, CommitmentItemType.DECISION, null, null, CommitmentDecisionStatus.CHOSEN, "미래 일정은 UI에 노출하고 과거 맥락은 사람 중심으로 정리", "3번 관련해서 이미지처럼 정리되는게 best라고 생각합니다", now.minusHours(4), null, null),
                commitment("qa-cmt-local-first-memory", PERSON_JIHOON, localFirstMemoryId, CommitmentItemType.DECISION, null, null, CommitmentDecisionStatus.APPROVED, "원문은 로컬에 두고 memory.md와 DB를 분리 관리", "DB를 분리해서 관리하는 건 찬성합니다", now.minusHours(3), null, null),
                commitment("qa-cmt-history-window", PERSON_JIHOON, historyWindowId, CommitmentItemType.DECISION, null, null, CommitmentDecisionStatus.APPROVED, "사람별 interaction history를 3개월까지 확장", "3개월까지 history를 늘리는 건 문제가 전혀 안될듯", now.minusHours(2), null, null),
                commitment("qa-cmt-old-email-import", PERSON_JIHOON, oldEmailImportId, CommitmentItemType.ACTION, "give", null, null, "이전 이메일을 가져와 사람 중심 interaction 채우기", "이전 이메일 다 긁어오자", now.minusHours(1), null, null),
            ),
        )
        db.personActionDao().applyFeedSnapshot(
            userId = USER_ID,
            rows = listOf(
                personAction(
                    id = "qa-pa-youngkyung-time-followup",
                    personId = PERSON_KIM_YOUNGKYUNG,
                    personDisplayName = "김영경",
                    title = "김영경 센터장님 미팅 시간을 오늘 확정",
                    primaryVerb = "확인",
                    shortReason = "일정 조율 캡처에서 정확한 시간을 다시 알려주기로 했습니다.",
                    actionKind = "confirm_schedule",
                    commitmentId = "qa-cmt-youngkyung-time-followup",
                    sourceEventId = kimYoungkyungScheduleId,
                    sourceRef = "raw:$kimYoungkyungScheduleId",
                    dueAt = now.plusHours(4),
                    dueHint = "오늘",
                    urgencyScore = 98.0,
                    updatedAt = now.minusHours(8),
                ),
                personAction(
                    id = "qa-pa-chaerin-contact-centers",
                    personId = PERSON_KIM_CHAERIN,
                    personDisplayName = "김채린",
                    title = "센터장님들께 멘토링 일정 연락",
                    primaryVerb = "연락",
                    shortReason = "스타트업 익스프레스 답변에서 직접 연락해 일자를 잡으라고 안내했습니다.",
                    actionKind = "follow_up",
                    commitmentId = "qa-cmt-startup-contact-centers",
                    sourceEventId = startupReplyId,
                    sourceRef = "raw:$startupReplyId",
                    dueAt = now.plusHours(8),
                    dueHint = "오늘",
                    urgencyScore = 91.0,
                    updatedAt = now.minusHours(7),
                ),
                personAction(
                    id = "qa-pa-park-availability",
                    personId = PERSON_PARK_JINKYU,
                    personDisplayName = "박진규",
                    title = "박진규 센터장님 가능 시간 팔로업",
                    primaryVerb = "팔로업",
                    shortReason = "미팅 가능 시간을 편하게 알려달라고 요청한 캡처가 아직 열려 있습니다.",
                    actionKind = "follow_up",
                    commitmentId = "qa-cmt-park-jinkyu-availability",
                    sourceEventId = parkJinkyuId,
                    sourceRef = "raw:$parkJinkyuId",
                    dueAt = now.plusHours(12),
                    dueHint = "오늘",
                    urgencyScore = 86.0,
                    updatedAt = now.minusHours(5),
                ),
                personAction(
                    id = "qa-pa-jihoon-old-email",
                    personId = PERSON_JIHOON,
                    personDisplayName = "Jihoon Kang",
                    title = "이전 이메일 import 범위 결정 반영",
                    primaryVerb = "정리",
                    shortReason = "이전 이메일을 가져와 사람 중심 interaction을 채우기로 한 결정에서 나온 실행 항목입니다.",
                    actionKind = "do_work",
                    commitmentId = "qa-cmt-old-email-import",
                    sourceEventId = oldEmailImportId,
                    sourceRef = "raw:$oldEmailImportId",
                    dueAt = now.plusHours(24),
                    dueHint = "내일",
                    urgencyScore = 76.0,
                    updatedAt = now.minusHours(1),
                ),
            ),
            deletedIds = emptyList(),
            replaceScope = true,
            surface = "person",
            syncState = PersonActionSyncStateEntity(
                userId = USER_ID,
                surfaceKey = "person",
                status = "active",
                serverWatermark = now,
                recomputeState = "caught_up",
                capacityState = "normal",
                lastSyncedAt = now,
                updatedAt = now,
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                sourceInteraction(PERSON_KIM_HYUNSOO, SourceType.MESSAGE_SCREENSHOT, "raw:$kimHyunsooSmsId", kimHyunsooSmsId, "message", "counterparty", null, null, now.minusHours(10), "문자 캡처 - 김현수 팀장님", "약속 장소 도착과 도착 예정 시간을 확인했습니다."),
                sourceInteraction(PERSON_KIM_YOUNGKYUNG, SourceType.MESSAGE_SCREENSHOT, "raw:$kimYoungkyungIntroId", kimYoungkyungIntroId, "message", "counterparty", null, null, now.minusHours(9), "카카오톡 캡처 - 김영경 센터장님 소개", "현황 설명과 피드백 미팅을 요청했습니다."),
                sourceInteraction(PERSON_KIM_YOUNGKYUNG, SourceType.MESSAGE_SCREENSHOT, "raw:$kimYoungkyungScheduleId", kimYoungkyungScheduleId, "message", "counterparty", null, null, now.minusHours(8), "카카오톡 캡처 - 김영경 센터장님 일정 조율", "다음 주 화요일 오후 미팅과 시간 재연락이 잡혔습니다."),
                sourceInteraction(PERSON_KIM_CHAERIN, SourceType.MESSAGE_SCREENSHOT, "raw:$startupReplyId", startupReplyId, "email", "counterparty", null, null, now.minusHours(7), "메일 캡처 - 스타트업 익스프레스 답변", "센터장님들께 직접 연락해 멘토링 일정을 잡으라는 안내를 받았습니다."),
                sourceInteraction(PERSON_PARK_JINKYU, SourceType.MESSAGE_SCREENSHOT, "raw:$parkJinkyuId", parkJinkyuId, "message", "counterparty", null, null, now.minusHours(5), "문자 캡처 - 박진규 센터장님 미팅 요청", "사업 현황 설명과 피드백 미팅 가능 시간을 요청했습니다."),
                sourceInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "raw:$relationArchitectureId", relationArchitectureId, "message", "counterparty", null, null, now.minusHours(4), "메신저 캡처 - 관계 지능 UI 결정", "관계 지능 UI와 일정 노출 원칙을 논의했습니다."),
                sourceInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "raw:$localFirstMemoryId", localFirstMemoryId, "message", "counterparty", null, null, now.minusHours(3), "메신저 캡처 - local-first와 memory.md", "원문 로컬 보관과 memory.md 분리 관리에 동의했습니다."),
                sourceInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "raw:$historyWindowId", historyWindowId, "message", "counterparty", null, null, now.minusHours(2), "메신저 캡처 - interaction history 범위", "사람별 history window를 3개월까지 확장하기로 했습니다."),
                sourceInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "raw:$oldEmailImportId", oldEmailImportId, "message", "counterparty", null, null, now.minusHours(1), "메신저 캡처 - 이전 이메일 동기화", "이전 이메일을 가져와 사람 중심 interaction을 채우기로 했습니다."),

                commitmentInteraction(PERSON_KIM_YOUNGKYUNG, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-youngkyung-meeting-request", kimYoungkyungIntroId, CommitmentItemType.ACTION, "take", "pending", now.minusHours(9), "김영경 센터장님과 현황 설명 및 피드백 미팅 조율"),
                commitmentInteraction(PERSON_KIM_YOUNGKYUNG, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-youngkyung-schedule", kimYoungkyungScheduleId, CommitmentItemType.SCHEDULE, null, "confirmed", now.minusHours(8), "다음 주 화요일 오후 김영경 센터장님 미팅"),
                commitmentInteraction(PERSON_KIM_YOUNGKYUNG, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-youngkyung-time-followup", kimYoungkyungScheduleId, CommitmentItemType.ACTION, "take", "pending", now.minusHours(8), "김영경 센터장님이 정확한 시간을 다시 알려주기"),
                commitmentInteraction(PERSON_KIM_CHAERIN, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-startup-contact-centers", startupReplyId, CommitmentItemType.ACTION, "give", "pending", now.minusHours(7), "센터장님들께 직접 연락해 멘토링 일자 잡기"),
                commitmentInteraction(PERSON_PARK_JINKYU, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-park-jinkyu-availability", parkJinkyuId, CommitmentItemType.ACTION, "take", "pending", now.minusHours(5), "박진규 센터장님이 가능한 미팅 시간 알려주기"),
                commitmentInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-relation-ui-decision", relationArchitectureId, CommitmentItemType.DECISION, null, "chosen", now.minusHours(4), "미래 일정은 UI에 노출하고 과거 맥락은 사람 중심으로 정리"),
                commitmentInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-local-first-memory", localFirstMemoryId, CommitmentItemType.DECISION, null, "approved", now.minusHours(3), "원문은 로컬에 두고 memory.md와 DB를 분리 관리"),
                commitmentInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-history-window", historyWindowId, CommitmentItemType.DECISION, null, "approved", now.minusHours(2), "사람별 interaction history를 3개월까지 확장"),
                commitmentInteraction(PERSON_JIHOON, SourceType.MESSAGE_SCREENSHOT, "qa-cmt-old-email-import", oldEmailImportId, CommitmentItemType.ACTION, "give", "pending", now.minusHours(1), "이전 이메일을 가져와 사람 중심 interaction 채우기"),
            ),
        )
        // Local debug seeds use issuer-shaped fake JWTs. Do not enqueue remote memory upload
        // workers with those sessions; a backend 401 would intentionally clear the local session.
    }

    private suspend fun seedMeetingSpeakerJourney() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)
        val customerEmail = "customer@example.com"
        val customerPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, customerEmail)).personId
        val rawEventId = "qa-meeting-speaker-raw"
        val sourceRef = "qa-meeting-speaker:meeting_001.wav"

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        userPrefsStore.setThirdPartyProvisionConsent(true)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(USER_ID),
                refreshToken = "debug-refresh-token",
                userId = USER_ID,
                email = "debug.meeting.speaker@becalm.local",
                expiresAt = expiresAt,
            ),
        )

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        val db = databaseProvider.current()
        clearDemoRows(db)
        seedConnectedSources(now)

        db.rawIngestionEventDao().upsertSyncedFromServer(
            listOf(
                RawIngestionEventEntity(
                    id = rawEventId,
                    userId = USER_ID,
                    clientEventId = "debug-client-$rawEventId",
                    sourceType = SourceType.MEETING,
                    sourceRef = sourceRef,
                    counterpartyRef = null,
                    eventTitle = "meeting_001.wav",
                    eventSnippet = "SPEAKER_02가 금요일까지 제안서를 공유하겠다고 말한 회의입니다.",
                    durationSeconds = 60,
                    commitmentsExtractedCount = 2,
                    timestamp = now.minusHours(1),
                    syncStatus = "synced",
                ),
            ),
        )
        db.personIndexDao().upsertPersons(
            listOf(person(customerPersonId, "Customer Lee", customerEmail, null, now)),
        )
        db.personIndexDao().upsertIdentities(
            listOf(identity(customerPersonId, "email", customerEmail, "Customer Lee", SourceType.MEETING, now, true)),
        )
        db.personEnrichmentDao().upsertAll(
            listOf(enrichment(customerEmail, "Customer Lee", "PM", now)),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = "qa-meeting-speaker-self",
                    userId = USER_ID,
                    sourceEventId = rawEventId,
                    sourceType = SourceType.MEETING,
                    sourceRef = sourceRef,
                    personId = null,
                    role = "speaker",
                    relationToUser = "self",
                    identityType = "speaker_label",
                    normalizedValue = "SPEAKER_01",
                    displayNameRaw = "SPEAKER_01",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "제가 일정 확인해서 공유하겠습니다.",
                    confidence = 1.0,
                    resolutionStatus = "resolved",
                    createdAt = now.minusHours(1),
                ),
                SourceEventParticipantEntity(
                    id = "qa-meeting-speaker-counterparty",
                    userId = USER_ID,
                    sourceEventId = rawEventId,
                    sourceType = SourceType.MEETING,
                    sourceRef = sourceRef,
                    personId = null,
                    role = "speaker",
                    relationToUser = "participant",
                    identityType = "speaker_label",
                    normalizedValue = "SPEAKER_02",
                    displayNameRaw = "SPEAKER_02",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "금요일까지 제안서 보내겠습니다.",
                    confidence = 0.0,
                    resolutionStatus = "unresolved",
                    createdAt = now.minusHours(1),
                ),
            ),
        )
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "qa-unmatched-meeting-speaker",
                    userId = USER_ID,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:$rawEventId",
                    interactionKind = "meeting",
                    title = "회의 녹음 - meeting_001.wav",
                    snippet = "SPEAKER_02: 금요일까지 제안서 보내겠습니다.",
                    suggestedLabel = "SPEAKER_02",
                    occurredAt = now.minusHours(1),
                    createdAt = now,
                ),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "qa-meeting-speaker-take",
                    personId = customerPersonId,
                    sourceEventId = rawEventId,
                    itemType = CommitmentItemType.ACTION,
                    direction = "take",
                    scheduleStatus = null,
                    decisionStatus = null,
                    title = "Customer Lee가 금요일까지 제안서 공유",
                    quote = "금요일까지 제안서 보내겠습니다.",
                    occurredAt = now.minusHours(1),
                    dueAt = null,
                    dueHint = "금요일",
                ).copy(
                    counterpartyRaw = "Customer Lee",
                    counterpartyRef = customerEmail,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:$rawEventId",
                    sourceEventTitle = "meeting_001.wav",
                ),
                commitment(
                    id = "qa-meeting-speaker-schedule",
                    personId = customerPersonId,
                    sourceEventId = rawEventId,
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    decisionStatus = null,
                    title = "다음 주 미팅 일정 확인",
                    quote = "다음 주 미팅 일정은 다시 확인하겠습니다.",
                    occurredAt = now.minusHours(1),
                    dueAt = null,
                    dueHint = "다음 주",
                ).copy(
                    counterpartyRaw = "Customer Lee",
                    counterpartyRef = customerEmail,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:$rawEventId",
                    sourceEventTitle = "meeting_001.wav",
                ),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                sourceInteraction(
                    personId = customerPersonId,
                    sourceType = SourceType.MEETING,
                    sourceRef = "raw:$rawEventId",
                    sourceEventId = rawEventId,
                    kind = "meeting",
                    role = "participant",
                    direction = null,
                    status = null,
                    occurredAt = now.minusHours(1),
                    title = "회의 녹음 - meeting_001.wav",
                    snippet = "금요일까지 제안서 공유와 다음 주 미팅 일정 확인이 논의되었습니다.",
                ),
                commitmentInteraction(
                    personId = customerPersonId,
                    sourceType = SourceType.MEETING,
                    commitmentId = "qa-meeting-speaker-take",
                    sourceEventId = rawEventId,
                    itemType = CommitmentItemType.ACTION,
                    direction = "take",
                    status = "pending",
                    occurredAt = now.minusHours(1),
                    title = "Customer Lee가 금요일까지 제안서 공유",
                ),
                commitmentInteraction(
                    personId = customerPersonId,
                    sourceType = SourceType.MEETING,
                    commitmentId = "qa-meeting-speaker-schedule",
                    sourceEventId = rawEventId,
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    status = "confirmed",
                    occurredAt = now.minusHours(1),
                    title = "다음 주 미팅 일정 확인",
                ),
            ),
        )
        // See seedDemoData: remote memory uploads are skipped for fake debug sessions.
    }

    private suspend fun seedScalePersonTimeline() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)
        val personId = PERSON_SCALE

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(USER_ID),
                refreshToken = "debug-refresh-token",
                userId = USER_ID,
                email = "debug.scale.timeline@becalm.local",
                expiresAt = expiresAt,
            ),
        )

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        val db = databaseProvider.current()
        clearDemoRows(db)
        seedConnectedSources(now)

        db.personIndexDao().upsertPersons(
            listOf(person(personId, "Scale Customer", "scale.customer@example.invalid", null, now)),
        )
        db.personIndexDao().upsertIdentities(
            listOf(identity(personId, "email", "scale.customer@example.invalid", "Scale Customer", SourceType.GMAIL, now, true)),
        )
        db.personEnrichmentDao().upsertAll(
            listOf(enrichment("scale.customer@example.invalid", "Scale Customer", "Scale QA", now)),
        )

        val rawEvents = (0 until SCALE_TIMELINE_COUNT).map { index ->
            val eventTime = now.minusHours(index.toLong())
            RawIngestionEventEntity(
                id = "qa-scale-raw-$index",
                userId = USER_ID,
                clientEventId = "qa-scale-client-$index",
                sourceType = SourceType.GMAIL,
                sourceRef = "qa-scale-mail:$index",
                counterpartyRef = "scale.customer@example.invalid",
                eventTitle = "Scale mail $index",
                eventSnippet = "Scale timeline item $index for device pagination smoke.",
                folder = if (index % 2 == 0) "INBOX" else "SENT",
                commitmentsExtractedCount = if (index % 5 == 0) 1 else 0,
                timestamp = eventTime,
                syncStatus = "synced",
            )
        }
        db.rawIngestionEventDao().upsertSyncedFromServer(rawEvents)
        db.personIndexDao().upsertSourceEventParticipants(
            rawEvents.map { event ->
                SourceEventParticipantEntity(
                    id = UUID.nameUUIDFromBytes("qa-scale-participant:${event.id}".toByteArray()).toString(),
                    userId = USER_ID,
                    sourceEventId = event.id,
                    sourceType = event.sourceType,
                    sourceRef = event.sourceRef,
                    personId = personId,
                    role = "counterparty",
                    relationToUser = "counterparty",
                    identityType = "email",
                    normalizedValue = "scale.customer@example.invalid",
                    displayNameRaw = "Scale Customer",
                    emailRaw = "scale.customer@example.invalid",
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = "Scale QA",
                    evidence = "Scale timeline device smoke",
                    confidence = 1.0,
                    resolutionStatus = "resolved",
                    createdAt = event.timestamp,
                )
            },
        )
        db.personIndexDao().upsertInteractions(
            rawEvents.map { event ->
                sourceInteraction(
                    personId = personId,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:${event.id}",
                    sourceEventId = event.id,
                    kind = "email",
                    role = "counterparty",
                    direction = null,
                    status = null,
                    occurredAt = event.timestamp,
                    title = event.eventTitle.orEmpty(),
                    snippet = event.eventSnippet.orEmpty(),
                )
            },
        )
        // See seedDemoData: remote memory uploads are skipped for fake debug sessions.
        Timber.i("Debug scale person timeline seeded personId=$personId count=$SCALE_TIMELINE_COUNT")
    }

    private suspend fun seedProcessingStatusSmoke() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(USER_ID),
                refreshToken = "debug-refresh-token",
                userId = USER_ID,
                email = "debug.processing.status@becalm.local",
                expiresAt = expiresAt,
            ),
        )

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        clearDemoRows(databaseProvider.current())
        seedConnectedSources(now)
        processingStatusRepository.recordError(
            sourceType = SourceType.GMAIL,
            message = ProcessingStatusMessages.SOURCE_SYNC_BACKPRESSURE_DELAYED,
        )
        processingStatusRepository.recordBlocked(
            sourceType = SourceType.MEETING,
            message = ProcessingStatusMessages.LLM_DAILY_BUDGET_EXCEEDED,
        )
        processingStatusRepository.recordError(
            sourceType = SourceType.VOICE,
            message = ProcessingStatusMessages.LLM_RATE_LIMITED_RETRYING,
        )
        Timber.i("Debug processing status smoke seeded actionNeededCount=3")
    }

    private suspend fun seedSyncFailureUiSmoke() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(USER_ID),
                refreshToken = "debug-refresh-token",
                userId = USER_ID,
                email = "debug.sync.failure.ui@becalm.local",
                expiresAt = expiresAt,
            ),
        )

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        clearDemoRows(databaseProvider.current())
        seedConnectedSources(now)
        sourceStatusRepository.recordSyncError(
            sourceType = SourceType.OUTLOOK_MAIL,
            error = "oauth_reauth_required",
            at = now,
        )
        processingStatusRepository.recordGemini(SourceType.GMAIL)
        processingStatusRepository.recordError(SourceType.OUTLOOK_MAIL)
        Timber.i("Debug sync failure UI smoke seeded activeSource=gmail actionSource=outlook_mail")
    }

    private suspend fun seedAccountSwapSmoke() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)
        val pid = android.os.Process.myPid()

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(ACCOUNT_SWAP_USER_A)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(ACCOUNT_SWAP_USER_A),
                refreshToken = "debug-refresh-token-a",
                userId = ACCOUNT_SWAP_USER_A,
                email = "debug.account.swap.a@becalm.local",
                expiresAt = expiresAt,
            ),
        )
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(ACCOUNT_SWAP_USER_A))
        clearMirrorRowsForUser(databaseProvider.current(), ACCOUNT_SWAP_USER_A)
        seedAccountSwapPerson(
            db = databaseProvider.current(),
            userId = ACCOUNT_SWAP_USER_A,
            personId = PERSON_ACCOUNT_SWAP_A,
            displayName = "Account A Contact",
            email = "account.a@example.invalid",
            now = now.minusHours(1),
        )

        userPrefsStore.setCurrentUserId(ACCOUNT_SWAP_USER_B)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(ACCOUNT_SWAP_USER_B),
                refreshToken = "debug-refresh-token-b",
                userId = ACCOUNT_SWAP_USER_B,
                email = "debug.account.swap.b@becalm.local",
                expiresAt = expiresAt,
            ),
        )
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(ACCOUNT_SWAP_USER_B))
        clearMirrorRowsForUser(databaseProvider.current(), ACCOUNT_SWAP_USER_B)
        seedAccountSwapPerson(
            db = databaseProvider.current(),
            userId = ACCOUNT_SWAP_USER_B,
            personId = PERSON_ACCOUNT_SWAP_B,
            displayName = "Account B Contact",
            email = "account.b@example.invalid",
            now = now,
        )
        Timber.i(
            "Debug account swap smoke seeded pid=$pid " +
                "fromHash=${BeCalmDatabase.deriveUserIdHash(ACCOUNT_SWAP_USER_A).take(4)} " +
                "toHash=${BeCalmDatabase.deriveUserIdHash(ACCOUNT_SWAP_USER_B).take(4)}",
        )
    }

    private suspend fun seedPrivacySmoke() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L)

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setCurrentUserId(USER_ID)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        userPrefsStore.setThirdPartyProvisionConsent(true)
        userPrefsStore.setSourceEnabled(SourceType.VOICE, true)
        sessionStore.save(
            SupabaseSession(
                accessToken = debugAccessToken(USER_ID),
                refreshToken = "debug-refresh-token",
                userId = USER_ID,
                email = "debug.privacy@becalm.local",
                expiresAt = expiresAt,
            ),
        )

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(USER_ID))
        val db = databaseProvider.current()
        clearDemoRows(db)
        db.rawIngestionEventDao().insert(
            RawIngestionEventEntity(
                id = "qa-privacy-voice-raw",
                userId = USER_ID,
                clientEventId = "qa-privacy-voice-client",
                sourceType = SourceType.VOICE,
                sourceRef = "qa-privacy-voice.wav",
                counterpartyRef = null,
                eventTitle = "Privacy smoke voice item",
                eventSnippet = "Voice item parked when consent is withdrawn.",
                durationSeconds = 30,
                timestamp = now,
                syncStatus = "pending",
            ),
        )
        Timber.i("Debug privacy smoke seeded voiceConsent=true pendingVoiceRows=1")
    }

    private suspend fun seedCorrectionSmoke(intent: Intent) {
        val userId = ensureE2eSession(intent)
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
        val db = databaseProvider.current()
        clearCorrectionSmokeRows(db, userId)
        sourceStatusRepository.recordSyncSuccess(SourceType.MESSAGE_SCREENSHOT, now)

        db.rawIngestionEventDao().upsertSyncedFromServer(
            listOf(
                RawIngestionEventEntity(
                    id = CORRECTION_SMOKE_EVENT_ID,
                    userId = userId,
                    clientEventId = CORRECTION_SMOKE_CLIENT_EVENT_ID,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = CORRECTION_SMOKE_SOURCE_REF,
                    counterpartyRef = "QA Wrong Person",
                    eventTitle = "QA correction smoke - wrong person",
                    eventSnippet = "Synthetic correction smoke row. No user content.",
                    folder = null,
                    commitmentsExtractedCount = 0,
                    timestamp = now.minusHours(1),
                    syncStatus = "synced",
                ),
            ),
        )
        db.personIndexDao().upsertPersons(
            listOf(
                person(CORRECTION_SMOKE_WRONG_PERSON_ID, "QA Wrong Person", null, null, now, userId = userId),
                person(CORRECTION_SMOKE_CORRECT_PERSON_ID, "QA Correct Person", null, null, now, userId = userId),
            ),
        )
        db.personIndexDao().upsertIdentities(
            listOf(
                identity(
                    personId = CORRECTION_SMOKE_WRONG_PERSON_ID,
                    type = "name",
                    value = "QA Wrong Person",
                    displayName = "QA Wrong Person",
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    now = now,
                    primary = true,
                    userId = userId,
                ),
                identity(
                    personId = CORRECTION_SMOKE_CORRECT_PERSON_ID,
                    type = "name",
                    value = "QA Correct Person",
                    displayName = "QA Correct Person",
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    now = now,
                    primary = true,
                    userId = userId,
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                SourceEventParticipantEntity(
                    id = CORRECTION_SMOKE_PARTICIPANT_ID,
                    userId = userId,
                    sourceEventId = CORRECTION_SMOKE_EVENT_ID,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "raw:$CORRECTION_SMOKE_EVENT_ID",
                    personId = CORRECTION_SMOKE_WRONG_PERSON_ID,
                    role = "counterparty",
                    relationToUser = "counterparty",
                    identityType = "name",
                    normalizedValue = "qa wrong person",
                    displayNameRaw = "QA Wrong Person",
                    emailRaw = null,
                    phoneRaw = null,
                    organizationRaw = null,
                    titleRaw = null,
                    evidence = "Synthetic correction smoke participant.",
                    confidence = 1.0,
                    resolutionStatus = "resolved",
                    createdAt = now.minusHours(1),
                ),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                PersonInteractionEntity(
                    id = CORRECTION_SMOKE_INTERACTION_ID,
                    userId = userId,
                    personId = CORRECTION_SMOKE_WRONG_PERSON_ID,
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = "raw:$CORRECTION_SMOKE_EVENT_ID",
                    interactionKind = "message",
                    sourceEventId = CORRECTION_SMOKE_EVENT_ID,
                    role = "counterparty",
                    direction = null,
                    status = null,
                    occurredAt = now.minusHours(1),
                    title = "QA correction smoke - wrong person",
                    snippet = "Synthetic correction smoke row. No user content.",
                    confidence = 1.0,
                    createdAt = now.minusHours(1),
                ),
            ),
        )
        Timber.i(
            "Debug correction smoke seeded userHash=${userId.shortHash()} " +
                "eventId=$CORRECTION_SMOKE_EVENT_ID participantId=$CORRECTION_SMOKE_PARTICIPANT_ID",
        )
    }

    private suspend fun reportCorrectionSmoke(intent: Intent) {
        val userId = intent.getStringExtra(EXTRA_USER_ID)?.takeIf { it.isNotBlank() }
            ?: userPrefsStore.observeCurrentUserId().first()
            ?: error("No active userId for correction smoke report")
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
        val db = databaseProvider.current()
        val participantState = db.readSingleString(
            """
            SELECT COALESCE(resolution_status, '') || '|' || COALESCE(person_id, '')
            FROM source_event_participants
            WHERE user_id = ? AND id = ?
            """.trimIndent(),
            arrayOf(userId, CORRECTION_SMOKE_PARTICIPANT_ID),
        ) ?: "missing"
        val correctionCount = db.countRows(
            "SELECT COUNT(*) FROM user_corrections WHERE user_id = ? AND target_id = ?",
            arrayOf(userId, CORRECTION_SMOKE_PARTICIPANT_ID),
        )
        val pendingSyncCount = db.countRows(
            """
            SELECT COUNT(*) FROM user_corrections
            WHERE user_id = ? AND target_id = ? AND sync_status = 'pending'
            """.trimIndent(),
            arrayOf(userId, CORRECTION_SMOKE_PARTICIPANT_ID),
        )
        val syncedCount = db.countRows(
            """
            SELECT COUNT(*) FROM user_corrections
            WHERE user_id = ? AND target_id = ? AND sync_status = 'synced'
            """.trimIndent(),
            arrayOf(userId, CORRECTION_SMOKE_PARTICIPANT_ID),
        )
        val failedCount = db.countRows(
            """
            SELECT COUNT(*) FROM user_corrections
            WHERE user_id = ? AND target_id = ? AND sync_status = 'failed'
            """.trimIndent(),
            arrayOf(userId, CORRECTION_SMOKE_PARTICIPANT_ID),
        )
        val interactionCount = db.countRows(
            "SELECT COUNT(*) FROM person_interactions WHERE user_id = ? AND source_event_id = ?",
            arrayOf(userId, CORRECTION_SMOKE_EVENT_ID),
        )
        Timber.i(
            "Debug correction smoke report userHash=${userId.shortHash()} " +
                "participantState=$participantState corrections=$correctionCount " +
                "pendingSync=$pendingSyncCount synced=$syncedCount failed=$failedCount " +
                "interactions=$interactionCount",
        )
    }

    private suspend fun seedAccountSwapPerson(
        db: BeCalmDatabase,
        userId: String,
        personId: String,
        displayName: String,
        email: String,
        now: Instant,
    ) {
        val rawEventId = "qa-account-swap-raw-$personId"
        db.personIndexDao().upsertPersons(
            listOf(person(personId, displayName, email, null, now, userId = userId)),
        )
        db.personIndexDao().upsertIdentities(
            listOf(identity(personId, "email", email, displayName, SourceType.GMAIL, now, true, userId = userId)),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                sourceInteraction(
                    personId = personId,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:$rawEventId",
                    sourceEventId = rawEventId,
                    kind = "email",
                    role = "counterparty",
                    direction = null,
                    status = null,
                    occurredAt = now,
                    title = "Account swap proof for $displayName",
                    snippet = "Only the current account should render this relationship.",
                    userId = userId,
                ),
            ),
        )
    }

    private suspend fun prepareStagingMirrorSmoke(intent: Intent) {
        val userId = ensureE2eSession(intent)
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE)?.takeIf { it.isNotBlank() } ?: SourceType.GMAIL
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        userPrefsStore.setTermsAccepted(true)
        userPrefsStore.setOnboardingCompleted(true)
        userPrefsStore.setProcessingPaused(false)
        if (sourceType == SourceType.GMAIL) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.GMAIL, true)
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true)
            userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, true)
        }
        if (sourceType == SourceType.OUTLOOK_MAIL) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.OUTLOOK_MAIL, true)
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL, true)
            userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.OUTLOOK_MAIL, true)
        }
        if (sourceType != SourceType.GMAIL && sourceType != SourceType.OUTLOOK_MAIL) {
            userPrefsStore.setSourceEnabled(sourceType, true)
        }

        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
        val db = databaseProvider.current()
        clearMirrorRowsForUser(db, userId)
        SourceMirrorCursorReset.clearForSourceType(syncCursorStore, userId, sourceType)
        sourceStatusRepository.recordSyncSuccess(sourceType, now)
        workScheduler.enqueueSourceRelationRefresh(sourceType, initialDelaySeconds = 0L)
        Timber.i(
            "Debug staging mirror smoke prepared sourceType=$sourceType " +
                "userHash=${userId.shortHash()}",
        )
    }

    private suspend fun reportStagingMirrorSmoke(intent: Intent) {
        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE)?.takeIf { it.isNotBlank() } ?: SourceType.GMAIL
        val userId = intent.getStringExtra(EXTRA_USER_ID)?.takeIf { it.isNotBlank() }
            ?: userPrefsStore.observeCurrentUserId().first()
            ?: error("No active userId for staging mirror smoke report")
        databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(userId))
        val db = databaseProvider.current()
        val rawCount = db.countRows(
            "SELECT COUNT(*) FROM raw_ingestion_events WHERE user_id = ? AND source_type = ?",
            arrayOf(userId, sourceType),
        )
        val sourceParticipantCount = db.countRows(
            "SELECT COUNT(*) FROM source_event_participants WHERE user_id = ? AND source_type = ?",
            arrayOf(userId, sourceType),
        )
        val commitmentCount = db.countRows(
            "SELECT COUNT(*) FROM commitments WHERE user_id = ? AND source_type = ?",
            arrayOf(userId, sourceType),
        )
        val commitmentParticipantCount = db.countRows(
            "SELECT COUNT(*) FROM commitment_participants WHERE user_id = ?",
            arrayOf(userId),
        )
        val rawCursorPresent = syncCursorStore.observeCursor("raw_ingestion_events:$sourceType").first() != null
        val sourceParticipantCursorPresent =
            syncCursorStore.observeCursor("source_event_participants:$sourceType").first() != null
        val commitmentCursorPresent = syncCursorStore.observeCursor("commitments_cursor").first() != null
        val commitmentParticipantCursorPresent = syncCursorStore.observeCursor("commitment_participants").first() != null
        Timber.i(
            "Debug staging mirror smoke report sourceType=$sourceType " +
                "userHash=${userId.shortHash()} rawCount=$rawCount " +
                "sourceParticipantCount=$sourceParticipantCount commitmentCount=$commitmentCount " +
                "commitmentParticipantCount=$commitmentParticipantCount " +
                "rawCursorPresent=$rawCursorPresent " +
                "sourceParticipantCursorPresent=$sourceParticipantCursorPresent " +
                "commitmentCursorPresent=$commitmentCursorPresent " +
                "commitmentParticipantCursorPresent=$commitmentParticipantCursorPresent",
        )
    }

    private suspend fun seedConnectedSources(now: Instant) {
        userPrefsStore.setCallLogMatchingConsent(true)
        userPrefsStore.setSourceEnabled(SourceType.VOICE, true)
        userPrefsStore.setSourceEnabled(SourceType.MEETING, true)
        userPrefsStore.setSourceEnabled(SourceType.GOOGLE_CALENDAR, true)
        userPrefsStore.setSourceEnabled(SourceType.OUTLOOK_CALENDAR, true)
        listOf(
            EmailPipaProvider.GMAIL,
            EmailPipaProvider.OUTLOOK_MAIL,
            EmailPipaProvider.NAVER_IMAP,
            EmailPipaProvider.DAUM_IMAP,
        ).forEach { provider ->
            userPrefsStore.setEmailPipaConsent(provider, true)
            userPrefsStore.setEmailSourceConnected(provider, true)
        }
        SourceType.PRODUCT_SOURCES.forEach { source ->
            sourceStatusRepository.recordSyncSuccess(source, now)
        }
    }

    private fun clearDemoRows(db: BeCalmDatabase) {
        val sql = db.openHelper.writableDatabase
        val args = arrayOf(USER_ID)
        sql.execSQL("DELETE FROM email_body WHERE raw_event_id IN (SELECT id FROM raw_ingestion_events WHERE user_id = ?)", args)
        sql.execSQL("DELETE FROM commitments WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM commitment_participants WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM source_event_participants WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM person_interactions WHERE user_id = ?", args)
        runCatching { sql.execSQL("DELETE FROM unmatched_person_interactions WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_index_dirty_sources WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_memory_semantic_index WHERE user_id = ?", args) }
        sql.execSQL("DELETE FROM person_identities WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM persons WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM raw_ingestion_events WHERE user_id = ?", args)
        runCatching { sql.execSQL("DELETE FROM person_action_item_cache WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_action_sync_state WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_action_mutation_queue WHERE user_id = ?", args) }
        sql.execSQL("DELETE FROM persons_enrichment")
    }

    private fun clearMirrorRowsForUser(db: BeCalmDatabase, userId: String) {
        val sql = db.openHelper.writableDatabase
        val args = arrayOf(userId)
        sql.execSQL("DELETE FROM email_body WHERE raw_event_id IN (SELECT id FROM raw_ingestion_events WHERE user_id = ?)", args)
        sql.execSQL("DELETE FROM commitments WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM commitment_participants WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM source_event_participants WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM person_interactions WHERE user_id = ?", args)
        runCatching { sql.execSQL("DELETE FROM unmatched_person_interactions WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_index_dirty_sources WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_memory_semantic_index WHERE user_id = ?", args) }
        sql.execSQL("DELETE FROM person_identities WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM persons WHERE user_id = ?", args)
        sql.execSQL("DELETE FROM raw_ingestion_events WHERE user_id = ?", args)
        runCatching { sql.execSQL("DELETE FROM person_action_item_cache WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_action_sync_state WHERE user_id = ?", args) }
        runCatching { sql.execSQL("DELETE FROM person_action_mutation_queue WHERE user_id = ?", args) }
        sql.execSQL("DELETE FROM persons_enrichment")
    }

    private fun clearCorrectionSmokeRows(db: BeCalmDatabase, userId: String) {
        val sql = db.openHelper.writableDatabase
        val userAndParticipant = arrayOf(userId, CORRECTION_SMOKE_PARTICIPANT_ID)
        val userAndEvent = arrayOf(userId, CORRECTION_SMOKE_EVENT_ID)
        sql.execSQL(
            "DELETE FROM user_corrections WHERE user_id = ? AND target_id = ?",
            userAndParticipant,
        )
        sql.execSQL(
            "DELETE FROM source_event_participants WHERE user_id = ? AND id = ?",
            userAndParticipant,
        )
        sql.execSQL(
            "DELETE FROM person_interactions WHERE user_id = ? AND source_event_id = ?",
            userAndEvent,
        )
        sql.execSQL(
            "DELETE FROM raw_ingestion_events WHERE user_id = ? AND id = ?",
            userAndEvent,
        )
        sql.execSQL(
            "DELETE FROM person_identities WHERE user_id = ? AND person_id IN (?, ?)",
            arrayOf(userId, CORRECTION_SMOKE_WRONG_PERSON_ID, CORRECTION_SMOKE_CORRECT_PERSON_ID),
        )
        sql.execSQL(
            "DELETE FROM persons WHERE user_id = ? AND id IN (?, ?)",
            arrayOf(userId, CORRECTION_SMOKE_WRONG_PERSON_ID, CORRECTION_SMOKE_CORRECT_PERSON_ID),
        )
        runCatching {
            sql.execSQL(
                "DELETE FROM person_index_dirty_sources WHERE user_id = ? AND source_event_id = ?",
                userAndEvent,
            )
        }
    }

    private fun BeCalmDatabase.countRows(sql: String, args: Array<String>): Int =
        openHelper.readableDatabase.query(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun BeCalmDatabase.readSingleString(sql: String, args: Array<String>): String? =
        openHelper.readableDatabase.query(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun rawEvent(
        id: String,
        sourceType: String,
        sourceRef: String,
        counterpartyRef: String,
        title: String,
        snippet: String,
        timestamp: Instant,
        extractedCount: Int,
        folder: String? = null,
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = "debug-client-$id",
            sourceType = sourceType,
            sourceRef = sourceRef,
            counterpartyRef = counterpartyRef,
            eventTitle = title,
            eventSnippet = snippet,
            folder = folder,
            commitmentsExtractedCount = extractedCount,
            timestamp = timestamp,
            syncStatus = "synced",
        )

    private fun enrichment(
        personRef: String,
        displayName: String,
        title: String?,
        now: Instant,
    ): PersonEnrichmentEntity =
        PersonEnrichmentEntity(
            personRef = personRef,
            displayName = displayName,
            nickname = null,
            company = null,
            title = title,
            sourceContactId = "qa-contact-${personRef.lowercase().replace(" ", "-")}",
            lastSyncedAt = now,
        )

    private fun participant(
        sourceEventId: String,
        personId: String,
        displayName: String,
        role: String,
        relationToUser: String,
        evidence: String,
        occurredAt: Instant,
        title: String?,
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = UUID.nameUUIDFromBytes("qa-participant:$USER_ID:$sourceEventId:$personId:$role".toByteArray()).toString(),
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = SourceType.MESSAGE_SCREENSHOT,
            sourceRef = "qa-message-screenshot:$sourceEventId",
            personId = personId,
            role = role,
            relationToUser = relationToUser,
            identityType = "name",
            normalizedValue = displayName.lowercase(),
            displayNameRaw = displayName,
            emailRaw = null,
            phoneRaw = null,
            organizationRaw = null,
            titleRaw = title,
            evidence = evidence,
            confidence = 1.0,
            resolutionStatus = "resolved",
            createdAt = occurredAt,
        )

    private fun commitment(
        id: String,
        personId: String,
        sourceEventId: String,
        itemType: String,
        direction: String?,
        scheduleStatus: String?,
        decisionStatus: String?,
        title: String,
        quote: String,
        occurredAt: Instant,
        dueAt: Instant?,
        dueHint: String?,
    ): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = USER_ID,
            itemType = itemType,
            direction = direction,
            scheduleStatus = scheduleStatus,
            decisionStatus = decisionStatus,
            counterpartyRaw = personDisplayName(personId),
            counterpartyRef = personDisplayName(personId),
            title = title,
            description = null,
            quote = quote,
            sourceEventTitle = "메신저 스크린샷",
            sourceEventOccurredAt = occurredAt,
            dueAt = dueAt,
            dueHint = dueHint,
            dueIsApproximate = dueAt != null,
            actionState = "pending",
            sourceType = SourceType.MESSAGE_SCREENSHOT,
            sourceRef = "raw:$sourceEventId",
            confidence = 1.0,
            syncStatus = "synced",
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )

    private fun personDisplayName(personId: String): String =
        when (personId) {
            PERSON_KIM_HYUNSOO -> "김현수"
            PERSON_KIM_YOUNGKYUNG -> "김영경"
            PERSON_KIM_CHAERIN -> "김채린"
            PERSON_PARK_JINKYU -> "박진규"
            PERSON_JIHOON -> "Jihoon Kang"
            PERSON_KYE -> "Kye Lim"
            else -> personId
        }

    private fun person(
        id: String,
        displayName: String,
        email: String?,
        phone: String?,
        now: Instant,
        userId: String = USER_ID,
    ): PersonEntity =
        PersonEntity(
            id = id,
            userId = userId,
            displayName = displayName,
            kind = "contact",
            primaryEmail = email,
            primaryPhone = phone,
            confidence = 1.0,
            createdAt = now,
            updatedAt = now,
            archivedAt = null,
        )

    private fun identity(
        personId: String,
        type: String,
        value: String,
        displayName: String,
        sourceType: String,
        now: Instant,
        primary: Boolean,
        userId: String = USER_ID,
    ): PersonIdentityEntity {
        val normalized = when (type) {
            "email" -> value.lowercase()
            else -> value
        }
        return PersonIdentityEntity(
            id = UUID.nameUUIDFromBytes("debug-identity:$userId:$personId:$type:$normalized".toByteArray()).toString(),
            userId = userId,
            personId = personId,
            identityKey = "$type:$normalized",
            identityType = type,
            rawValue = normalized,
            displayNameHint = displayName,
            sourceType = sourceType,
            confidence = 1.0,
            isPrimary = primary,
            verified = true,
            lastSeenAt = now,
        )
    }

    private fun sourceInteraction(
        personId: String,
        sourceType: String,
        sourceRef: String,
        sourceEventId: String,
        kind: String,
        role: String,
        direction: String?,
        status: String?,
        occurredAt: Instant,
        title: String,
        snippet: String,
        userId: String = USER_ID,
    ): PersonInteractionEntity =
        PersonInteractionEntity(
            id = UUID.nameUUIDFromBytes("debug-interaction:$sourceRef:$kind:$personId".toByteArray()).toString(),
            userId = userId,
            personId = personId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            interactionKind = kind,
            sourceEventId = sourceEventId,
            role = role,
            direction = direction,
            status = status,
            occurredAt = occurredAt,
            title = title,
            snippet = snippet,
            confidence = 1.0,
        )

    private fun commitmentInteraction(
        personId: String,
        sourceType: String,
        commitmentId: String,
        sourceEventId: String,
        itemType: String,
        direction: String?,
        status: String,
        occurredAt: Instant,
        title: String,
    ): PersonInteractionEntity =
        PersonInteractionEntity(
            id = UUID.nameUUIDFromBytes("debug-interaction:$commitmentId:$personId".toByteArray()).toString(),
            userId = USER_ID,
            personId = personId,
            sourceType = sourceType,
            sourceRef = "commitment:$commitmentId",
            interactionKind = "commitment",
            sourceEventId = sourceEventId,
            commitmentId = commitmentId,
            role = itemType,
            direction = direction,
            status = status,
            occurredAt = occurredAt,
            title = title,
            snippet = title,
            confidence = 1.0,
        )

    private fun personAction(
        id: String,
        personId: String,
        personDisplayName: String,
        title: String,
        primaryVerb: String,
        shortReason: String,
        actionKind: String,
        commitmentId: String,
        sourceEventId: String,
        sourceRef: String,
        dueAt: Instant,
        dueHint: String,
        urgencyScore: Double,
        updatedAt: Instant,
    ): PersonActionItemCacheEntity =
        PersonActionItemCacheEntity(
            id = id,
            userId = USER_ID,
            personId = personId,
            personDisplayName = personDisplayName,
            personSortKey = personDisplayName.lowercase(),
            surfacesCsv = "person,commitment",
            actionKind = actionKind,
            status = "active",
            title = title,
            primaryVerb = primaryVerb,
            shortReason = shortReason,
            commitmentId = commitmentId,
            calendarEventId = null,
            sourceEventId = sourceEventId,
            sourceType = SourceType.MESSAGE_SCREENSHOT,
            sourceRef = sourceRef,
            dueAt = dueAt,
            dueHint = dueHint,
            dueIsApproximate = true,
            staleAfter = dueAt.plusHours(24),
            urgencyScore = urgencyScore,
            importanceScore = 80.0,
            confidence = 1.0,
            reasonCodesCsv = "debug_source_seed,source:message_screenshot",
            inputWatermark = updatedAt,
            serverWatermark = updatedAt,
            computedAt = updatedAt,
            updatedAt = updatedAt,
            snoozedUntil = null,
            completedAt = null,
            dismissedAt = null,
        )

    private fun Instant.minusHours(hours: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() - hours * 60L * 60L * 1000L)

    private fun Instant.plusHours(hours: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + hours * 60L * 60L * 1000L)

    private fun String.shortHash(): Int = hashCode()

    private fun debugAccessToken(userId: String): String {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val issuer = BuildConfig.SUPABASE_URL
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
            ?.let { "$it/auth/v1" }
            ?: "debug"
        val header = """{"alg":"none","typ":"JWT"}"""
        val payload = """
            {"iss":"${issuer.jsonEscaped()}","sub":"${userId.jsonEscaped()}","aud":"authenticated","iat":$nowSeconds,"exp":${nowSeconds + 365L * 24L * 60L * 60L}}
        """.trimIndent()
        return "${header.base64Url()}." +
            "${payload.base64Url()}." +
            AuthInterceptor.DEBUG_LOCAL_ONLY_TOKEN_SIGNATURE
    }

    private fun String.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

    private fun String.jsonEscaped(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val ACTION = "com.becalm.android.DEBUG_SEED_PERSON_RENDERING"
        const val ACTION_SEED_SCALE_PERSON_TIMELINE = "com.becalm.android.DEBUG_SEED_SCALE_PERSON_TIMELINE"
        const val ACTION_PREPARE_STAGING_MIRROR_SMOKE = "com.becalm.android.DEBUG_PREPARE_STAGING_MIRROR_SMOKE"
        const val ACTION_REPORT_STAGING_MIRROR_SMOKE = "com.becalm.android.DEBUG_REPORT_STAGING_MIRROR_SMOKE"
        const val ACTION_ENQUEUE_CLOVA_AUDIO_E2E = "com.becalm.android.DEBUG_ENQUEUE_CLOVA_AUDIO_E2E"
        const val ACTION_SEED_MEETING_SPEAKER_JOURNEY = "com.becalm.android.DEBUG_SEED_MEETING_SPEAKER_JOURNEY"
        const val ACTION_SEED_SYNC_FAILURE_UI_SMOKE = "com.becalm.android.DEBUG_SEED_SYNC_FAILURE_UI_SMOKE"
        const val ACTION_SEED_PROCESSING_STATUS_SMOKE = "com.becalm.android.DEBUG_SEED_PROCESSING_STATUS_SMOKE"
        const val ACTION_SEED_ACCOUNT_SWAP_SMOKE = "com.becalm.android.DEBUG_SEED_ACCOUNT_SWAP_SMOKE"
        const val ACTION_SEED_PRIVACY_SMOKE = "com.becalm.android.DEBUG_SEED_PRIVACY_SMOKE"
        const val ACTION_SEED_CORRECTION_SMOKE = "com.becalm.android.DEBUG_SEED_CORRECTION_SMOKE"
        const val ACTION_REPORT_CORRECTION_SMOKE = "com.becalm.android.DEBUG_REPORT_CORRECTION_SMOKE"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_DURATION_SECONDS = "duration_seconds"
        const val EXTRA_SOURCE_TYPE = "source_type"
        const val EXTRA_EVENT_TITLE = "event_title"
        const val EXTRA_COUNTERPARTY_REF = "counterparty_ref"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_REFRESH_TOKEN = "refresh_token"
        const val EXTRA_EMAIL = "email"
        const val EXTRA_EXPIRES_AT_EPOCH_MS = "expires_at_epoch_ms"
        const val CORRECTION_SMOKE_EVENT_ID = "10000000-0000-4000-8000-000000000001"
        const val CORRECTION_SMOKE_PARTICIPANT_ID = "10000000-0000-4000-8000-000000000002"
        const val CORRECTION_SMOKE_WRONG_PERSON_ID = "10000000-0000-4000-8000-000000000003"
        const val CORRECTION_SMOKE_CORRECT_PERSON_ID = "10000000-0000-4000-8000-000000000004"
        const val CORRECTION_SMOKE_INTERACTION_ID = "10000000-0000-4000-8000-000000000005"
        const val CORRECTION_SMOKE_CLIENT_EVENT_ID = "10000000-0000-4000-8000-000000000006"
        const val CORRECTION_SMOKE_SOURCE_REF = "qa-correction-smoke:10000000-0000-4000-8000-000000000001"
        const val USER_ID = "00000000-0000-4000-8000-000000000001"
        const val ACCOUNT_SWAP_USER_A = "debug-account-swap-user-a"
        const val ACCOUNT_SWAP_USER_B = "debug-account-swap-user-b"
        const val PERSON_KIM_HYUNSOO = "qa-person-kim-hyunsoo"
        const val PERSON_KIM_YOUNGKYUNG = "qa-person-kim-youngkyung"
        const val PERSON_KIM_CHAERIN = "qa-person-kim-chaerin"
        const val PERSON_PARK_JINKYU = "qa-person-park-jinkyu"
        const val PERSON_JIHOON = "qa-person-jihoon-kang"
        const val PERSON_KYE = "qa-person-kye-lim"
        const val PERSON_SCALE = "qa-scale-person"
        const val PERSON_ACCOUNT_SWAP_A = "qa-account-swap-a"
        const val PERSON_ACCOUNT_SWAP_B = "qa-account-swap-b"
        const val SCALE_TIMELINE_COUNT = 220
    }
}
