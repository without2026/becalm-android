package com.becalm.android.unit.ui.evidence

import com.becalm.android.R
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.evidence.EvidenceImportPersistentStatus
import com.becalm.android.ui.evidence.EvidenceImportStatusProjectionPort
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceImportViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val sourceImportRepository: SourceImportRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `persistent projection status is restored by a new ViewModel instance`() = runTest {
        val recoveredStatus = EvidenceImportPersistentStatus.PROCESSING
        val first = EvidenceImportViewModel(sourceImportRepository, FakeStatusProjectionPort(recoveredStatus))
        advanceUntilIdle()
        assertEquals(
            UiMessage.resource(R.string.evidence_import_status_processing),
            first.awaitStatusMessage(),
        )

        val restored = EvidenceImportViewModel(sourceImportRepository, FakeStatusProjectionPort(recoveredStatus))
        advanceUntilIdle()
        assertEquals(
            UiMessage.resource(R.string.evidence_import_status_processing),
            restored.awaitStatusMessage(),
        )
    }

    private suspend fun EvidenceImportViewModel.awaitStatusMessage(): UiMessage =
        requireNotNull(withTimeout(5_000) { state.first { it.statusMessage != null }.statusMessage })

    private class FakeStatusProjectionPort(
        status: EvidenceImportPersistentStatus,
    ) : EvidenceImportStatusProjectionPort {
        private val statusFlow = MutableStateFlow(status)
        override fun observeStatus(): Flow<EvidenceImportPersistentStatus> = statusFlow
    }
}
