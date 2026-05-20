package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.RawEventCommitmentRow
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Additional projection seam for raw-event drill-down fields that are not carried directly
 * on [RawIngestionEventEntity].
 */
public interface RawEventDetailProjectionPort {
    public suspend fun loadCommitmentQuotes(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<String>

    public suspend fun loadCommitmentSummaries(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventCommitmentSummary>

    public suspend fun loadCalendarAttendeesRaw(
        userId: String,
        event: RawIngestionEventEntity,
    ): String?
}

@Singleton
public class RoomBackedRawEventDetailProjectionPort @Inject constructor(
    private val commitmentDao: CommitmentDao,
    private val calendarEventDao: CalendarEventDao,
) : RawEventDetailProjectionPort {
    override suspend fun loadCommitmentQuotes(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<String> {
        val sourceRef = event.sourceRef ?: return emptyList()
        return commitmentDao.findQuotesBySourceRefForUser(
            userId = userId,
            sourceRef = sourceRef,
        )
    }

    override suspend fun loadCommitmentSummaries(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventCommitmentSummary> {
        val sourceRefs = event.commitmentSourceRefs()
        if (sourceRefs.isEmpty()) return emptyList()
        return commitmentDao.findRawEventCommitmentsBySourceRefsForUser(
            userId = userId,
            sourceRefs = sourceRefs,
        ).map { it.toUiSummary() }
    }

    override suspend fun loadCalendarAttendeesRaw(
        userId: String,
        event: RawIngestionEventEntity,
    ): String? {
        val sourceRef = event.sourceRef ?: return null
        if (event.sourceType !in setOf(SourceType.GOOGLE_CALENDAR, SourceType.OUTLOOK_CALENDAR)) {
            return null
        }
        return calendarEventDao.findBySourceRefForUser(
            userId = userId,
            sourceType = event.sourceType,
            sourceRef = sourceRef,
        )?.attendeesRaw
    }

    private fun RawIngestionEventEntity.commitmentSourceRefs(): List<String> =
        listOfNotNull(
            sourceRef,
            "raw:$id",
            id,
            clientEventId,
        ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun RawEventCommitmentRow.toUiSummary(): RawEventCommitmentSummary =
        RawEventCommitmentSummary(
            id = id,
            title = title,
            itemType = itemType,
            direction = direction,
            status = status,
            quote = quote,
        )
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class RawEventDetailProjectionModule {

    @Binds
    @Singleton
    public abstract fun bindRawEventDetailProjectionPort(
        impl: RoomBackedRawEventDetailProjectionPort,
    ): RawEventDetailProjectionPort
}
