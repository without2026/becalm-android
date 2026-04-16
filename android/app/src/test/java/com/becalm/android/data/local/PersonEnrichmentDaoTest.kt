package com.becalm.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.entities.PersonEnrichment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// spec: ENR-003 — UPSERT by person_ref PRIMARY KEY
// spec: ENR-004 — separate records for phone vs email person_refs
// spec: ENR-006 — fallback: UI shows person_ref when no enrichment
// spec: ENR-007 — deleteAll on logout (PIPA)

@RunWith(RobolectricTestRunner::class)
class PersonEnrichmentDaoTest {

    private lateinit var db: BeCalmDatabase
    private lateinit var dao: PersonEnrichmentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.personEnrichmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // spec: ENR-003 — upsert creates new enrichment
    @Test
    fun `upsert creates new person enrichment`() = runTest {
        val enrichment = PersonEnrichment(
            personRef = "+821012345678",
            displayName = "김철수",
            company = "ABC Corp",
            title = "팀장"
        )
        dao.upsert(enrichment)
        val result = dao.getByPersonRef("+821012345678")
        assertNotNull(result)
        assertEquals("김철수", result!!.displayName)
        assertEquals("ABC Corp", result.company)
        assertEquals("팀장", result.title)
    }

    // spec: ENR-003 — upsert replaces existing enrichment
    @Test
    fun `upsert replaces existing enrichment for same person_ref`() = runTest {
        dao.upsert(PersonEnrichment(personRef = "+821012345678", displayName = "Old Name"))
        dao.upsert(PersonEnrichment(personRef = "+821012345678", displayName = "New Name"))
        val result = dao.getByPersonRef("+821012345678")
        assertEquals("New Name", result!!.displayName)
        assertEquals(1, dao.count())
    }

    // spec: ENR-004 — same contact with different person_refs gets separate records
    @Test
    fun `phone and email person_refs for same contact get separate enrichment records`() = runTest {
        dao.upsert(PersonEnrichment(personRef = "+821012345678", displayName = "김철수"))
        dao.upsert(PersonEnrichment(personRef = "kim@corp.com", displayName = "김철수"))
        assertEquals(2, dao.count())
        assertNotNull(dao.getByPersonRef("+821012345678"))
        assertNotNull(dao.getByPersonRef("kim@corp.com"))
    }

    // spec: ENR-006 — getByPersonRef returns null for unknown person_ref
    @Test
    fun `getByPersonRef returns null when no enrichment exists`() = runTest {
        val result = dao.getByPersonRef("unknown@domain.com")
        assertNull(result)
    }

    // spec: ENR-007 — deleteAll on logout (PIPA compliance)
    @Test
    fun `deleteAll removes all enrichment records on logout`() = runTest {
        dao.upsert(PersonEnrichment(personRef = "+821012345678", displayName = "김철수"))
        dao.upsert(PersonEnrichment(personRef = "kim@corp.com", displayName = "김이메일"))
        dao.deleteAll()
        assertEquals(0, dao.count())
    }

    // spec: ENR-003 — getByPersonRefs batch query
    @Test
    fun `getByPersonRefs returns only requested person_refs`() = runTest {
        dao.upsert(PersonEnrichment(personRef = "ref-1", displayName = "A"))
        dao.upsert(PersonEnrichment(personRef = "ref-2", displayName = "B"))
        dao.upsert(PersonEnrichment(personRef = "ref-3", displayName = "C"))
        val results = dao.getByPersonRefs(listOf("ref-1", "ref-3"))
        assertEquals(2, results.size)
        val names = results.map { it.displayName }.toSet()
        assertEquals(setOf("A", "C"), names)
    }
}
