package com.becalm.android.worker

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class SourceConnectionLocalStateHydrator @Inject constructor(
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) {
    public suspend fun hydrate(userId: String) {
        when (val result = sourceConnectionRepository.refresh(userId)) {
            is BecalmResult.Success -> applyConnections(result.value)
            is BecalmResult.Failure -> logger.w(TAG, "source connection hydration failed")
        }
        when (sourceStatusRepository.refreshFromServer()) {
            is BecalmResult.Success -> Unit
            is BecalmResult.Failure -> logger.w(TAG, "source status hydration failed")
        }
    }

    private suspend fun applyConnections(connections: List<SourceConnectionEntity>) {
        val activeSources = connections
            .filter { it.isRuntimeActive() }
            .mapNotNull { it.runtimeSourceType() }
            .toSet()

        setBackendMail(EmailPipaProvider.GMAIL, SourceType.GMAIL in activeSources)
        setBackendMail(EmailPipaProvider.OUTLOOK_MAIL, SourceType.OUTLOOK_MAIL in activeSources)
        userPrefsStore.setSourceEnabled(SourceType.GOOGLE_CALENDAR, SourceType.GOOGLE_CALENDAR in activeSources)
        userPrefsStore.setSourceEnabled(SourceType.OUTLOOK_CALENDAR, SourceType.OUTLOOK_CALENDAR in activeSources)

        activeSources
            .filter { it in BACKEND_MIRROR_SOURCES }
            .forEach { sourceType ->
                workScheduler.enqueueSourceRelationRefresh(sourceType = sourceType, initialDelaySeconds = 0L)
            }
        logger.d(TAG, "source connection local state hydrated activeSources=$activeSources")
    }

    private suspend fun setBackendMail(provider: EmailPipaProvider, connected: Boolean) {
        userPrefsStore.setEmailSourceConnected(provider, connected)
        userPrefsStore.setEmailSourceManagedByBackend(provider, connected)
    }

    private companion object {
        private const val TAG = "SourceConnectionHydrator"
        private val ACTIVE_STATUSES = setOf("active", "connected", "syncing", "synced")
        private val BACKEND_MIRROR_SOURCES = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )

        private fun SourceConnectionEntity.isRuntimeActive(): Boolean =
            status.trim().lowercase() in ACTIVE_STATUSES

        private fun SourceConnectionEntity.runtimeSourceType(): String? =
            when {
                provider == "google" && capability == "mail" -> SourceType.GMAIL
                provider == "outlook" && capability == "mail" -> SourceType.OUTLOOK_MAIL
                provider == "google" && capability == "calendar" -> SourceType.GOOGLE_CALENDAR
                provider == "outlook" && capability == "calendar" -> SourceType.OUTLOOK_CALENDAR
                provider == SourceType.GMAIL -> SourceType.GMAIL
                provider == SourceType.OUTLOOK_MAIL -> SourceType.OUTLOOK_MAIL
                provider == SourceType.GOOGLE_CALENDAR -> SourceType.GOOGLE_CALENDAR
                provider == SourceType.OUTLOOK_CALENDAR -> SourceType.OUTLOOK_CALENDAR
                else -> null
            }
    }
}
