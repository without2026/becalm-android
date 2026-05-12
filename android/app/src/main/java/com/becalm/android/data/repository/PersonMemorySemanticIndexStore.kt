package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.db.PersonMemorySemanticIndexJson
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonMemorySemanticIndexEntity
import com.becalm.android.domain.person.PersonMemoryInput
import com.becalm.android.domain.person.PersonMemorySemanticIndexBuilder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

public class PersonMemorySemanticIndexStore @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public suspend fun upsert(input: PersonMemoryInput): PersonMemorySemanticIndexEntity =
        withContext(ioDispatcher) {
            val index = PersonMemorySemanticIndexBuilder.build(input)
            val row = PersonMemorySemanticIndexEntity(
                personId = index.personId,
                userId = index.userId,
                displayNameTermsJson = PersonMemorySemanticIndexJson.encode(index.displayNameTerms),
                aliasesJson = PersonMemorySemanticIndexJson.encode(index.aliases),
                organizationsJson = PersonMemorySemanticIndexJson.encode(index.organizations),
                titlesJson = PersonMemorySemanticIndexJson.encode(index.titles),
                workTermsJson = PersonMemorySemanticIndexJson.encode(index.workTerms),
                decisionTermsJson = PersonMemorySemanticIndexJson.encode(index.decisionTerms),
                openCommitmentTermsJson = PersonMemorySemanticIndexJson.encode(index.openCommitmentTerms),
                confirmedPatternsJson = PersonMemorySemanticIndexJson.encode(index.confirmedPatterns),
                rejectedPatternsJson = PersonMemorySemanticIndexJson.encode(index.rejectedPatterns),
                recentSourceTypesJson = PersonMemorySemanticIndexJson.encode(index.recentSourceTypes),
                contentHash = index.contentHash,
                updatedAt = Clock.System.now(),
            )
            personIndexDao.upsertSemanticIndexes(listOf(row))
            row
        }
}
