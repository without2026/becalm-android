package com.becalm.android.integration.local.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonIndexDirtySources
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.worker.PersonInteractionIndexWorker
import com.becalm.android.worker.WorkScheduler
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonInteractionIndexWorkerLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val userPrefsStore = UserPrefsStoreImpl(
        LocalIntegrationSupport.prefsDataStore("person-index-worker-prefs"),
    )
    private val logger = RecordingLogger()
    private val scheduler = RecordingWorkScheduler()
    private val dispatcher = UnconfinedTestDispatcher()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `relation participants are the only projection source and suppress legacy person refs`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val legacyPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "legacy@example.com")).personId
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-mail-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = "legacy@example.com",
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "commitment-1",
                    counterpartyRef = "legacy@example.com",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "${SourceType.GMAIL}-ref-raw-mail-1",
                ),
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-1",
                    sourceEventId = "raw-mail-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                commitmentParticipant(
                    id = "commitment-participant-1",
                    commitmentId = "commitment-1",
                    personId = personId,
                    role = "owner",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        val interactions = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first()
        assertEquals(setOf("raw:raw-mail-1", "commitment:commitment-1"), interactions.map { it.sourceRef }.toSet())
        val commitmentInteraction = interactions.single { it.sourceRef == "commitment:commitment-1" }
        assertEquals("raw-mail-1", commitmentInteraction.sourceEventId)
        assertEquals("commitment-1", commitmentInteraction.commitmentId)
        assertNotNull(db.personIndexDao().findPersonForMemory(USER_ID, personId))
        val legacyInteractions = db.personIndexDao().observeInteractionsForPerson(USER_ID, legacyPersonId, limit = 20).first()
        assertTrue(legacyInteractions.isEmpty())
        assertEquals(listOf(personId), scheduler.profileMemoryPersonIds)
    }

    @Test
    fun `source local speaker labels do not create unmatched person review rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-call-speaker",
                sourceType = SourceType.CALL_RECORDING,
                counterpartyRef = null,
                eventSnippet = "SPEAKER_02: 금요일 일정으로 바꿔주세요.",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-speaker",
                    sourceEventId = "raw-call-speaker",
                    sourceType = SourceType.CALL_RECORDING,
                    sourceRef = "call-file",
                    personId = null,
                    email = null,
                    displayName = "SPEAKER_02",
                    role = "speaker",
                    relationToUser = "counterparty",
                    resolutionStatus = "unresolved",
                ).copy(
                    identityType = "speaker_label",
                    normalizedValue = "SPEAKER_02",
                    evidence = "SPEAKER_02",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20).isEmpty())
        assertTrue(scheduler.profileMemoryPersonIds.isEmpty())
    }

    @Test
    fun `e2e 063 person memory is enqueued after graph projection changes`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-memory-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-memory-1",
                    sourceEventId = "raw-memory-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-memory-1",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(
            listOf("raw:raw-memory-1"),
            db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first()
                .map { it.sourceRef },
        )
        assertEquals(listOf(personId), scheduler.profileMemoryPersonIds)
    }

    @Test
    fun `duplicate participants for one source and person render as one interaction`() = runTest {
        // spec: SRC-008
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-duplicate-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-duplicate-sender",
                    sourceEventId = "raw-duplicate-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-duplicate",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
                sourceParticipant(
                    id = "participant-duplicate-mentioned",
                    sourceEventId = "raw-duplicate-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-duplicate",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "mentioned",
                    relationToUser = "referenced",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        val interactions = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first()
        assertEquals(listOf("raw:raw-duplicate-1"), interactions.map { it.sourceRef })
        assertEquals(listOf(personId), scheduler.profileMemoryPersonIds)
    }

    @Test
    fun `dirty source participant with server event id uses local raw event for person detail`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val localRawEventId = "raw-local-naver-1"
        val serverSourceEventId = "server-source-event-1"
        val sourceRef = "naver-message-source-ref-1"
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = localRawEventId,
                sourceType = SourceType.NAVER_IMAP,
                sourceRef = sourceRef,
                counterpartyRef = null,
                eventTitle = "네이버 메일 제목",
                eventSnippet = "네이버 메일 본문 요약",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-server-event-id",
                    sourceEventId = serverSourceEventId,
                    sourceType = SourceType.NAVER_IMAP,
                    sourceRef = sourceRef,
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )
        db.personIndexDao().upsertDirtySources(
            listOf(
                PersonIndexDirtySources.rawEvent(
                    userId = USER_ID,
                    sourceType = SourceType.NAVER_IMAP,
                    sourceEventId = serverSourceEventId,
                    reason = "server-participant-refresh",
                    now = Instant.parse("2026-04-29T05:00:00Z"),
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        val interaction = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first().single()
        assertEquals("raw:$localRawEventId", interaction.sourceRef)
        assertEquals(localRawEventId, interaction.sourceEventId)
        assertEquals("네이버 메일 제목", interaction.title)
        assertEquals("네이버 메일 본문 요약", interaction.snippet)
    }

    @Test
    fun `dirty commitment rebuild also restores linked mail source interaction and strongest display name`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        assertDirtyCommitmentRestoresLinkedMailSource(
            sourceType = SourceType.NAVER_IMAP,
            rawEventId = "raw-naver-linked-1",
            mail = "gogo-naver@example.test",
        )
        assertDirtyCommitmentRestoresLinkedMailSource(
            sourceType = SourceType.GMAIL,
            rawEventId = "raw-gmail-linked-1",
            mail = "gogo-gmail@example.test",
        )
    }

    private suspend fun assertDirtyCommitmentRestoresLinkedMailSource(
        sourceType: String,
        rawEventId: String,
        mail: String,
    ) {
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, mail)).personId
        val sourceRef = "$sourceType-ref-$rawEventId"
        val commitmentId = "commitment-$rawEventId"
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = rawEventId,
                sourceType = sourceType,
                counterpartyRef = null,
                eventTitle = "MINI 모두의 창업 발표 참석 안내",
                eventSnippet = "한남대학교 창업지원단에서 행사 참석 일정을 안내드립니다.",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-$rawEventId-strong-name",
                    sourceEventId = rawEventId,
                    sourceType = sourceType,
                    sourceRef = sourceRef,
                    personId = personId,
                    email = mail,
                    displayName = "고주영",
                    role = "sender",
                    relationToUser = "counterparty",
                ),
                sourceParticipant(
                    id = "participant-$rawEventId-weak-name",
                    sourceEventId = rawEventId,
                    sourceType = sourceType,
                    sourceRef = sourceRef,
                    personId = personId,
                    email = mail,
                    displayName = null,
                    organization = "한남대",
                    role = "mentioned",
                    relationToUser = "referenced",
                ),
            ),
        )
        val commitment = commitment(
            id = commitmentId,
            counterpartyRef = null,
            sourceType = sourceType,
            sourceRef = sourceRef,
        )
        db.commitmentDao().insertAll(listOf(commitment))
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                commitmentParticipant(
                    id = "commitment-participant-$rawEventId",
                    commitmentId = commitment.id,
                    personId = personId,
                    role = "owner",
                ),
            ),
        )
        db.personIndexDao().upsertDirtySources(
            listOf(
                PersonIndexDirtySources.commitment(
                    userId = USER_ID,
                    commitmentId = commitment.id,
                    reason = "test-linked-mail",
                    now = Instant.parse("2026-04-29T05:00:00Z"),
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        val interactions = db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first()
        assertEquals(
            setOf("email", "commitment"),
            interactions.map { it.interactionKind }.toSet(),
        )
        assertEquals(
            setOf("raw:$rawEventId", "commitment:$commitmentId"),
            interactions.map { it.sourceRef }.toSet(),
        )
        assertEquals("고주영", db.personIndexDao().findPersonForMemory(USER_ID, personId)?.displayName)
        assertEquals(
            listOf("고주영"),
            db.personIndexDao().findIdentitiesForMemory(USER_ID, personId).map { it.displayNameHint }.distinct(),
        )
    }

    @Test
    fun `raw commitments and calendar rows without relation rows do not create people index rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-legacy-only",
                sourceType = SourceType.GMAIL,
                counterpartyRef = CUSTOMER_EMAIL,
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "commitment-legacy-only",
                    counterpartyRef = CUSTOMER_EMAIL,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "raw:raw-legacy-only",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 20).first().isEmpty())
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20).isEmpty())
    }

    @Test
    fun `unresolved source participants go to review without creating people rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-unresolved-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-unresolved",
                    sourceEventId = "raw-unresolved-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-2",
                    personId = null,
                    email = null,
                    displayName = "Steve",
                    role = "mentioned",
                    relationToUser = "referenced",
                    resolutionStatus = "unresolved",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 20).first().isEmpty())
        val unmatched = db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20)
        assertEquals(listOf("Steve"), unmatched.map { it.suggestedLabel })
    }

    @Test
    fun `service account verification emails do not create person matching review rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-slack-verification",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
                eventTitle = "Slack에서 이메일 주소를 확인하세요.",
                eventSnippet = "Slack을 시작하려면 이메일 주소를 확인하세요. 워크스페이스를 찾거나 새 워크스페이스를 생성할 수 있습니다.",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-slack-self",
                    sourceEventId = "raw-slack-verification",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-slack",
                    personId = null,
                    email = "me@example.com",
                    displayName = "me@example.com",
                    role = "mentioned",
                    relationToUser = "counterparty",
                    resolutionStatus = "suggested_self",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20).isEmpty())
    }

    @Test
    fun `program application support emails do not create person matching review rows`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-asan-doers",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
                eventTitle = "아산 두어스 지원 접수 안내",
                eventSnippet = "아산 두어스 프로그램 지원서가 정상 접수되었습니다. 선발 결과는 추후 안내됩니다.",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-asan-doers",
                    sourceEventId = "raw-asan-doers",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-asan-doers",
                    personId = null,
                    email = null,
                    displayName = "아산 두어스",
                    role = "sender",
                    relationToUser = "counterparty",
                    resolutionStatus = "unresolved",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20).isEmpty())
    }

    @Test
    fun `same source event email and phone participant rows project to one person`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val email = "minhong@example.com"
        val phone = "+821012345678"
        val emailPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, email)).personId
        val phonePersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, phone)).personId
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-email-phone-1",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-email",
                    sourceEventId = "raw-email-phone-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-email-phone",
                    personId = emailPersonId,
                    email = email,
                    displayName = "김민홍",
                    role = "sender",
                    relationToUser = "counterparty",
                    resolutionStatus = "person_resolved",
                ),
                sourceParticipant(
                    id = "participant-phone",
                    sourceEventId = "raw-email-phone-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-email-phone",
                    personId = phonePersonId,
                    email = null,
                    phone = phone,
                    displayName = "김민홍",
                    role = "sender",
                    relationToUser = "counterparty",
                    resolutionStatus = "person_resolved",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        val aggregates = db.personIndexDao().observeAggregates(USER_ID, limit = 20).first()
        assertEquals(listOf(emailPersonId), aggregates.map { it.personId })
        assertTrue(db.personIndexDao().observeInteractionsForPerson(USER_ID, phonePersonId, limit = 20).first().isEmpty())
        val identities = db.personIndexDao().findIdentitiesForMemory(USER_ID, emailPersonId)
        assertEquals(setOf("email", "phone"), identities.map { it.identityType }.toSet())
    }

    @Test
    fun `program application senders do not become people even when backend resolved them`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "startup@asan-nanum.org")).personId
        db.rawIngestionEventDao().insert(
            rawEvent(
                id = "raw-asan-doers-resolved",
                sourceType = SourceType.GMAIL,
                counterpartyRef = null,
                eventTitle = "[아산 두어스] 2026 아산 두어스 지원서 제출이 완료되었습니다.",
                eventSnippet = "아산나눔재단입니다. 지원서가 정상적으로 제출되었습니다. 서류 결과 안내: 4.30(목) 17:00",
            ),
        )
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-asan-staff-startup",
                    sourceEventId = "raw-asan-doers-resolved",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-asan-doers",
                    personId = personId,
                    email = "startup@asan-nanum.org",
                    displayName = "Staff Startup",
                    role = "sender",
                    relationToUser = "counterparty",
                    resolutionStatus = "person_resolved",
                ),
            ),
        )

        val result = newWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(db.personIndexDao().observeAggregates(USER_ID, limit = 20).first().isEmpty())
        assertTrue(db.personIndexDao().findUnmatchedInteractions(USER_ID, limit = 20).isEmpty())
    }

    @Test
    fun `blocked relation participants are removed on next index rebuild`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-blocked-1", sourceType = SourceType.GMAIL, counterpartyRef = null))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-blocked",
                    sourceEventId = "raw-blocked-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-3",
                    personId = personId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )

        newWorker().doWork()
        assertEquals(1, db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first().size)

        userPrefsStore.blockPersonRef(CUSTOMER_EMAIL)
        newWorker().doWork()

        assertTrue(db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first().isEmpty())
    }

    @Test
    fun `dirty source queue reindexes only queued source projection`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val firstPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val updatedPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, UPDATED_EMAIL)).personId
        val untouchedPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, UNTOUCHED_EMAIL)).personId
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-dirty-1", sourceType = SourceType.GMAIL, counterpartyRef = null))
        db.rawIngestionEventDao().insert(rawEvent(id = "raw-dirty-2", sourceType = SourceType.GMAIL, counterpartyRef = null))
        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-dirty-1",
                    sourceEventId = "raw-dirty-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = firstPersonId,
                    email = CUSTOMER_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
                sourceParticipant(
                    id = "participant-dirty-2",
                    sourceEventId = "raw-dirty-2",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-2",
                    personId = untouchedPersonId,
                    email = UNTOUCHED_EMAIL,
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )

        newWorker().doWork()
        logger.clear()
        scheduler.clear()

        db.personIndexDao().upsertSourceEventParticipants(
            listOf(
                sourceParticipant(
                    id = "participant-dirty-1",
                    sourceEventId = "raw-dirty-1",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "gmail-message-1",
                    personId = updatedPersonId,
                    email = UPDATED_EMAIL,
                    displayName = "Updated Customer",
                    role = "sender",
                    relationToUser = "counterparty",
                ),
            ),
        )
        db.personIndexDao().upsertDirtySources(
            listOf(
                PersonIndexDirtySources.rawEvent(
                    userId = USER_ID,
                    sourceType = SourceType.GMAIL,
                    sourceEventId = "raw-dirty-1",
                    reason = "test",
                    now = Instant.parse("2026-04-29T05:00:00Z"),
                ),
            ),
        )

        newWorker().doWork()

        assertTrue(db.personIndexDao().observeInteractionsForPerson(USER_ID, firstPersonId, limit = 20).first().isEmpty())
        assertEquals(
            listOf("raw:raw-dirty-1"),
            db.personIndexDao().observeInteractionsForPerson(USER_ID, updatedPersonId, limit = 20).first()
                .map { it.sourceRef },
        )
        assertEquals(
            listOf("raw:raw-dirty-2"),
            db.personIndexDao().observeInteractionsForPerson(USER_ID, untouchedPersonId, limit = 20).first()
                .map { it.sourceRef },
        )
        assertTrue(db.personIndexDao().findDirtySourcesForUser(USER_ID, limit = 10).isEmpty())
        assertTrue(logger.entries.any { "indexed mode=dirty dirtySources=1 changedSources=1" in it.message })
        assertEquals(setOf(firstPersonId, updatedPersonId), scheduler.profileMemoryPersonIds.toSet())
        assertTrue(untouchedPersonId !in scheduler.profileMemoryPersonIds)
    }

    @Test
    fun `dirty deleted commitment removes stale interaction and enqueues previous person memory`() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
        val personId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, CUSTOMER_EMAIL)).personId
        val row = commitment(
            id = "commitment-delete-1",
            counterpartyRef = CUSTOMER_EMAIL,
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-delete-1",
        )
        db.commitmentDao().insertAll(listOf(row))
        db.personIndexDao().upsertCommitmentParticipants(
            listOf(
                commitmentParticipant(
                    id = "commitment-participant-delete-1",
                    commitmentId = row.id,
                    personId = personId,
                    role = "owner",
                ),
            ),
        )
        newWorker().doWork()
        assertEquals(
            listOf("commitment:${row.id}"),
            db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first()
                .map { it.sourceRef },
        )
        scheduler.clear()

        db.commitmentDao().update(
            row.copy(
                deletedAt = Instant.parse("2026-04-29T05:00:00Z"),
                updatedAt = Instant.parse("2026-04-29T05:00:00Z"),
            ),
        )
        db.personIndexDao().upsertDirtySources(
            listOf(
                PersonIndexDirtySources.commitment(
                    userId = USER_ID,
                    commitmentId = row.id,
                    reason = "test-delete",
                    now = Instant.parse("2026-04-29T05:00:00Z"),
                ),
            ),
        )

        newWorker().doWork()

        assertTrue(db.personIndexDao().observeInteractionsForPerson(USER_ID, personId, limit = 20).first().isEmpty())
        assertEquals(listOf(personId), scheduler.profileMemoryPersonIds)
    }

    private fun newWorker(): PersonInteractionIndexWorker =
        PersonInteractionIndexWorker(
            appContext = LocalIntegrationSupport.appContext(),
            workerParams = LocalIntegrationSupport.workerParams(),
            databaseProvider = Provider { db },
            rawDaoProvider = Provider { db.rawIngestionEventDao() },
            commitmentDaoProvider = Provider { db.commitmentDao() },
            personIndexDaoProvider = Provider { db.personIndexDao() },
            selfIdentityAnchorDaoProvider = Provider { db.selfIdentityAnchorDao() },
            userPrefsStore = userPrefsStore,
            workScheduler = scheduler,
            logger = logger,
            ioDispatcher = dispatcher,
        )

    private class RecordingWorkScheduler : WorkScheduler {
        val profileMemoryPersonIds: MutableList<String> = mutableListOf()

        fun clear() {
            profileMemoryPersonIds.clear()
        }

        override fun enqueueProfileMemory(personId: String, initialDelaySeconds: Long) {
            profileMemoryPersonIds += personId
        }

        override fun enqueueExpedited(sourceKey: String) = Unit
        override fun enqueuePeriodic(sourceKey: String) = Unit
        override fun enqueueUpload(attempt: Int) = Unit
        override fun scheduleUploadRedundancy() = Unit
        override fun scheduleBackendMailSync() = Unit
        override fun enqueueEnrichment() = Unit
        override fun enqueuePersonInteractionIndex(initialDelaySeconds: Long) = Unit
        override fun enqueueSourceParticipantMirrorRetry(initialDelaySeconds: Long) = Unit
        override fun scheduleEnrichmentSweep() = Unit
        override fun cancelEnrichmentSweep() = Unit
        override fun enqueueVoiceUpload(rawEventId: String, audioUri: String, selfSpeakerId: String?, speakerMappingsJson: String?, speakerPreviewId: String?) = Unit
        override fun enqueueMessageScreenshotUpload(rawEventId: String) = Unit
        override fun enqueueMeetingSpeakerPreview(rawEventId: String, audioUri: String) = Unit
        override fun enqueueVoiceUploadWithDelay(
            rawEventId: String,
            audioUri: String,
            initialDelaySec: Long,
            rateLimitedAttempt: Int,
            selfSpeakerId: String?,
            speakerMappingsJson: String?,
            speakerPreviewId: String?,
            extractionJobId: String?,
            extractionJobPollAttempt: Int,
        ) = Unit
        override fun scheduleRetentionSweep() = Unit
        override fun scheduleOverdueSweep() = Unit
        override fun enqueueProcessDone(initialDelaySeconds: Long) = Unit
        override fun scheduleProcessDoneSweep() = Unit
        override fun enqueueDeferredColdSyncStage1() = Unit
        override fun enqueueColdSyncStage2() = Unit
        override fun cancelColdSyncStage2() = Unit
        override fun cancelVoiceUpload(rawEventId: String) = Unit
        override fun cancelMessageScreenshotUpload(rawEventId: String) = Unit
        override fun cancelAll() = Unit
        override fun cleanupLegacyWorkNames() = Unit
    }

    private fun rawEvent(
        id: String,
        sourceType: String,
        sourceRef: String = "$sourceType-ref-$id",
        counterpartyRef: String?,
        eventTitle: String = "event-$id",
        eventSnippet: String = "snippet-$id",
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = USER_ID,
            clientEventId = "client-$id",
            sourceType = sourceType,
            sourceRef = sourceRef,
            counterpartyRef = counterpartyRef,
            eventTitle = eventTitle,
            eventSnippet = eventSnippet,
            folder = "INBOX",
            timestamp = Instant.parse("2026-04-29T04:00:00Z"),
        )

    private fun commitment(
        id: String,
        counterpartyRef: String?,
        sourceType: String,
        sourceRef: String?,
    ): CommitmentEntity =
        CommitmentEntity(
            id = id,
            userId = USER_ID,
            itemType = CommitmentItemType.ACTION,
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
            counterpartyRaw = counterpartyRef,
            counterpartyRef = counterpartyRef,
            title = "commitment-$id",
            description = null,
            quote = "quote-$id",
            sourceEventTitle = "event-$id",
            sourceEventOccurredAt = Instant.parse("2026-04-29T04:00:00Z"),
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = sourceType,
            sourceRef = sourceRef,
            confidence = 0.9,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "synced",
            createdAt = Instant.parse("2026-04-29T04:00:00Z"),
            updatedAt = Instant.parse("2026-04-29T04:00:00Z"),
            lastEditedBy = null,
            lastEditedAt = null,
            quoteDisputed = false,
            quoteDisputedAt = null,
            deletedAt = null,
            supersedesCommitmentId = null,
        )

    private fun sourceParticipant(
        id: String,
        sourceEventId: String,
        sourceType: String,
        sourceRef: String,
        personId: String?,
        email: String?,
        phone: String? = null,
        displayName: String? = "Customer",
        organization: String? = null,
        role: String,
        relationToUser: String,
        resolutionStatus: String = if (personId == null) "unresolved" else "resolved",
    ): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = USER_ID,
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personId = personId,
            role = role,
            relationToUser = relationToUser,
            identityType = when {
                email != null -> "email"
                phone != null -> "phone"
                else -> null
            },
            normalizedValue = email ?: phone,
            displayNameRaw = displayName,
            emailRaw = email,
            phoneRaw = phone,
            organizationRaw = organization,
            titleRaw = null,
            evidence = email ?: phone ?: displayName,
            confidence = 0.95,
            resolutionStatus = resolutionStatus,
            createdAt = Instant.parse("2026-04-29T04:00:00Z"),
        )

    private fun commitmentParticipant(
        id: String,
        commitmentId: String,
        personId: String,
        role: String,
    ): CommitmentParticipantEntity =
        CommitmentParticipantEntity(
            id = id,
            userId = USER_ID,
            commitmentId = commitmentId,
            personId = personId,
            role = role,
            evidence = "quote",
            confidence = 0.9,
            createdAt = Instant.parse("2026-04-29T04:00:00Z"),
        )

    private companion object {
        const val USER_ID = "user-1"
        const val CUSTOMER_EMAIL = "customer@example.com"
        const val UPDATED_EMAIL = "updated@example.com"
        const val UNTOUCHED_EMAIL = "untouched@example.com"
    }
}
