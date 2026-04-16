package com.becalm.android.workers

import android.content.Context
import android.provider.ContactsContract
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.PersonEnrichment
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// spec: ENR-003 — read ContactsContract.Data, populate persons_enrichment
// spec: ENR-005 — 1-day periodic WorkManager + ContentObserver change detection
// spec: ENR-007 — delete all on logout (called directly from logout flow, not this worker)
// PIPA invariant: data NEVER uploaded to Railway or Supabase

@HiltWorker
class EnrichmentWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val personEnrichmentDao: PersonEnrichmentDao,
    private val rawIngestionEventDao: RawIngestionEventDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "enrichment-periodic"

        // spec: ENR-005 — 1-day periodic work
        fun schedulePeriodicWork(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<EnrichmentWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().build())
                .addTag(WORK_NAME)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        // spec: ENR-003 — check permission before reading contacts
        if (!hasContactsPermission()) return Result.success()

        // spec: ENR-003 — get all distinct person_refs from Room to enrich
        val personRefs = rawIngestionEventDao.getDistinctPersonRefs()
        if (personRefs.isEmpty()) return Result.success()

        val enrichments = mutableListOf<PersonEnrichment>()
        for (personRef in personRefs) {
            val enrichment = queryContactForPersonRef(personRef) ?: continue
            enrichments.add(enrichment)
        }

        // spec: ENR-004 — UPSERT by person_ref PRIMARY KEY
        if (enrichments.isNotEmpty()) {
            personEnrichmentDao.upsertAll(enrichments)
        }

        return Result.success()
    }

    // spec: ENR-003 — query ContactsContract for display_name, company, title
    // Only reads: StructuredName, Nickname, Organization — no SMS, call log, or photos
    private fun queryContactForPersonRef(personRef: String): PersonEnrichment? {
        // Determine lookup strategy by person_ref type
        val (lookupColumn, lookupValue) = when {
            personRef.startsWith("+") || personRef.matches(Regex("0[0-9]{9,10}")) -> {
                // Phone number — normalize to E.164 for lookup
                Pair(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, personRef)
            }
            personRef.contains("@") -> {
                // Email address
                Pair(ContactsContract.CommonDataKinds.Email.ADDRESS, personRef)
            }
            else -> return null // Unknown format — skip
        }

        val contactId = findContactId(lookupColumn, lookupValue) ?: return null
        return fetchContactDetails(contactId, personRef)
    }

    private fun findContactId(column: String, value: String): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "$column = ?",
            arrayOf(value),
            null
        ) ?: return null

        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun fetchContactDetails(contactId: String, personRef: String): PersonEnrichment? {
        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4
            ),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)",
            arrayOf(
                contactId,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
            ),
            null
        ) ?: return null

        var displayName: String? = null
        var nickname: String? = null
        var company: String? = null
        var title: String? = null
        var sourceContactId: String? = null

        cursor.use {
            while (it.moveToNext()) {
                val mimeType = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE))
                val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data._ID))
                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data.DATA1))
                        sourceContactId = id
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                        nickname = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data.DATA1))
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        company = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data.DATA1))
                        title = it.getString(it.getColumnIndexOrThrow(ContactsContract.Data.DATA4))
                    }
                }
            }
        }

        if (displayName == null && company == null) return null

        return PersonEnrichment(
            personRef = personRef,
            displayName = displayName,
            nickname = nickname,
            company = company,
            title = title,
            sourceContactId = sourceContactId,
            lastSyncedAt = System.currentTimeMillis()
        )
    }

    private fun hasContactsPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
}
