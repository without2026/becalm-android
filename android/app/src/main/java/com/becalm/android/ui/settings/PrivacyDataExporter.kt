package com.becalm.android.ui.settings

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SourceArtifactDao
import com.becalm.android.data.local.db.dao.UserProfileDao
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceArtifactEntity
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.scopes.ViewModelScoped
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant as JavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

public data class PrivacyExportPayload(
    val fileName: String,
    val bytes: ByteArray,
)

@ViewModelScoped
public class PrivacyDataExporter @Inject constructor(
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val calendarEventDao: CalendarEventDao,
    private val emailBodyDao: EmailBodyDao,
    private val sourceArtifactDao: SourceArtifactDao,
    private val personEnrichmentDao: PersonEnrichmentDao,
    private val userProfileDao: UserProfileDao,
    private val userPrefsStore: UserPrefsStore,
    private val moshi: Moshi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val rawEventsAdapter by lazy {
        moshi.adapter<List<RawIngestionEventEntity>>(
            Types.newParameterizedType(List::class.java, RawIngestionEventEntity::class.java),
        )
    }
    private val commitmentsAdapter by lazy {
        moshi.adapter<List<CommitmentEntity>>(
            Types.newParameterizedType(List::class.java, CommitmentEntity::class.java),
        )
    }
    private val calendarEventsAdapter by lazy {
        moshi.adapter<List<CalendarEventEntity>>(
            Types.newParameterizedType(List::class.java, CalendarEventEntity::class.java),
        )
    }
    private val emailBodiesAdapter by lazy {
        moshi.adapter<List<EmailBodyEntity>>(
            Types.newParameterizedType(List::class.java, EmailBodyEntity::class.java),
        )
    }
    private val sourceArtifactsAdapter by lazy {
        moshi.adapter<List<SourceArtifactEntity>>(
            Types.newParameterizedType(List::class.java, SourceArtifactEntity::class.java),
        )
    }
    private val enrichmentAdapter by lazy {
        moshi.adapter<List<PersonEnrichmentEntity>>(
            Types.newParameterizedType(List::class.java, PersonEnrichmentEntity::class.java),
        )
    }
    private val userProfileAdapter by lazy {
        moshi.adapter(UserProfileEntity::class.java).serializeNulls()
    }
    private val datastoreAdapter by lazy {
        moshi.adapter(Map::class.java).serializeNulls()
    }

    public suspend fun export(userId: String, nowEpochMs: Long): PrivacyExportPayload = withContext(ioDispatcher) {
        val rawEvents = rawIngestionEventDao.findAllForUser(userId)
        val commitments = commitmentDao.findAllForUser(userId)
        val calendarEvents = calendarEventDao.findAllForUser(userId)
        val emailBodies = emailBodyDao.findAllForUser(userId)
        val sourceArtifacts = sourceArtifactDao.findAllForUser(userId)
        val enrichment = personEnrichmentDao.observeAll().first()
        val userProfile = userProfileDao.findByUserId(userId)
        val datastoreDump = mapOf(
            "current_user_id" to userPrefsStore.observeCurrentUserId().first(),
            "onboarding_completed" to userPrefsStore.observeOnboardingCompleted().first(),
            "recording_folder_tree_uri" to userPrefsStore.observeRecordingFolderTreeUri().first(),
            "notifications_enabled" to userPrefsStore.observeNotificationsEnabled().first(),
            "pipa_third_party_consent" to userPrefsStore.observeThirdPartyProvisionConsent().first(),
            "processing_paused" to userPrefsStore.observeProcessingPaused().first(),
            "pause_started_at" to userPrefsStore.observePauseStartedAt().first(),
            "terms_accepted" to userPrefsStore.observeTermsAccepted().first(),
            "enabled_sources" to userPrefsStore.observeEnabledSources().first().sorted(),
            "pipa_action_log" to userPrefsStore.observePipaActionLog().first().map { entry ->
                mapOf(
                    "action" to entry.action,
                    "timestamp_iso" to entry.timestampIso,
                    "details" to entry.details,
                )
            },
            "email_connected" to EmailPipaProvider.entries.associate { provider ->
                provider.storageKey to userPrefsStore.observeEmailSourceConnected(provider).first()
            },
            "email_pipa_consent" to EmailPipaProvider.entries.associate { provider ->
                provider.storageKey to userPrefsStore.observeEmailPipaConsent(provider).first()
            },
        )

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            putJson(zip, "raw_ingestion_events.json", rawEventsAdapter.toJson(rawEvents))
            putJson(zip, "commitments.json", commitmentsAdapter.toJson(commitments))
            putJson(zip, "calendar_events.json", calendarEventsAdapter.toJson(calendarEvents))
            putJson(zip, "email_body.json", emailBodiesAdapter.toJson(emailBodies))
            putJson(zip, "source_artifacts.json", sourceArtifactsAdapter.toJson(sourceArtifacts))
            putJson(zip, "persons_enrichment.json", enrichmentAdapter.toJson(enrichment))
            putJson(zip, "user_profile.json", userProfileAdapter.toJson(userProfile))
            putJson(zip, "datastore.json", datastoreAdapter.toJson(datastoreDump))
            putReadme(zip)
        }
        PrivacyExportPayload(
            fileName = buildFilename(userId, nowEpochMs),
            bytes = out.toByteArray(),
        )
    }

    private fun putJson(zip: ZipOutputStream, name: String, payload: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(payload.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putReadme(zip: ZipOutputStream) {
        val text = """
            BeCalm personal data export
            
            Included:
            - raw_ingestion_events.json
            - commitments.json
            - calendar_events.json
            - email_body.json
            - source_artifacts.json (metadata only; Markdown file bodies are not included)
            - persons_enrichment.json
            - user_profile.json
            - datastore.json
            
            Excluded:
            - access_token / refresh_token
            - device keystore material
            - provider password hashes
            - server-side-only audit records
        """.trimIndent()
        zip.putNextEntry(ZipEntry("README.txt"))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildFilename(userId: String, nowEpochMs: Long): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.US)
            .format(JavaInstant.ofEpochMilli(nowEpochMs).atZone(ZoneId.of("Asia/Seoul")))
        return "becalm_export_${hash}_$timestamp.zip"
    }
}
