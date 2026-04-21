package com.becalm.android.data.repository

import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [EmailBodyRepositoryImpl] verifying the three lifecycle methods
 * delegate to [EmailBodyDao] without mutation. Repository owns the EMAIL-006
 * room-only invariant — these tests pin the surface area so that any future
 * accidental DTO mapper / wire fan-out becomes visible at the test layer.
 *
 * Spec ref: EMAIL-006, `.spec/contracts/data-model.yml:327-328 § email_body.room_only`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailBodyRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dao: EmailBodyDao = mockk(relaxed = true)
    private val repository: EmailBodyRepository = EmailBodyRepositoryImpl(
        dao = dao,
        ioDispatcher = testDispatcher,
    )

    private val sampleEntity = EmailBodyEntity(
        id = "body-1",
        rawEventId = "raw-1",
        providerMessageId = "gmail-msg-1",
        folder = FOLDER_INBOX,
        receivedAt = Instant.fromEpochMilliseconds(1000L),
    )

    @Test
    fun `insert delegates to dao insert exactly once`() = runTest(testDispatcher) {
        repository.insert(sampleEntity)

        coVerify(exactly = 1) { dao.insert(sampleEntity) }
    }

    @Test
    fun `getByRawEventId returns dao result unchanged`() = runTest(testDispatcher) {
        coEvery { dao.getByRawEventId("raw-1") } returns sampleEntity

        val result = repository.getByRawEventId("raw-1")

        assertEquals(sampleEntity, result)
        coVerify(exactly = 1) { dao.getByRawEventId("raw-1") }
    }

    @Test
    fun `getByRawEventId returns null when dao returns null`() = runTest(testDispatcher) {
        coEvery { dao.getByRawEventId("missing") } returns null

        val result = repository.getByRawEventId("missing")

        assertNull(result)
        coVerify(exactly = 1) { dao.getByRawEventId("missing") }
    }

    @Test
    fun `markParseFailed delegates to dao with same id`() = runTest(testDispatcher) {
        repository.markParseFailed("body-1")

        coVerify(exactly = 1) { dao.markParseFailed("body-1") }
    }
}
