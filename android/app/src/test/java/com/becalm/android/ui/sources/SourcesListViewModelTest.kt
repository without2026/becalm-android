package com.becalm.android.ui.sources

import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── SMG-VM-01: lists all product sources (PRODUCT_SOURCES, not schema ALL) ──

    @Test
    fun `state lists all product sources from SourceType PRODUCT_SOURCES`() = runTest {
        val now: Instant = Clock.System.now()
        val statuses = SourceType.PRODUCT_SOURCES.map { st ->
            SourceStatus(
                sourceType = st,
                status = SourceConnectionStatus.CONNECTED,
                lastSyncedAt = now,
                errorMessage = null,
            )
        }
        every { sourceStatusRepository.observeAll() } returns flowOf(statuses)

        val viewModel = SourcesListViewModel(
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            // Advance past the empty initial state if needed.
            if (emission.items.isEmpty()) emission = awaitItem()
            // VM prepends a pseudo-`contacts` row, so items.size = PRODUCT_SOURCES.size + 1.
            assertEquals(
                "Should have one row per PRODUCT_SOURCES entry plus the contacts pseudo-row",
                SourceType.PRODUCT_SOURCES.size + 1,
                emission.items.size,
            )
            val productTypes = emission.items
                .map { it.sourceType }
                .filter { it != "contacts" }
                .toSet()
            assertEquals(SourceType.PRODUCT_SOURCES, productTypes)
            // Product source rows should report CONNECTED status (contacts is always CONNECTED).
            assertTrue(emission.items.all { it.status == SourceConnectionStatus.CONNECTED.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── SMG-VM-02: disconnectSource logs warning (no API method exists) ───────

    @Test
    fun `disconnectSource logs warning because no repository API method exists`() = runTest {
        every { sourceStatusRepository.observeAll() } returns flowOf(emptyList())

        val viewModel = SourcesListViewModel(
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )

        viewModel.disconnectSource(SourceType.GMAIL)

        verify {
            logger.w(
                any(),
                match { it.contains(SourceType.GMAIL) && it.contains("no API method") },
                any(),
            )
        }
    }
}
