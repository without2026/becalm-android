package com.becalm.android.unit.ui.today

import com.becalm.android.ui.today.ColdSyncRouteResolver
import com.becalm.android.ui.today.ColdSyncStartupRoute
import com.becalm.android.ui.today.ColdSyncStartupSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ColdSyncRouteResolverSpecTest {

    private val resolver = ColdSyncRouteResolver()

    @Test
    fun `COLD-007 restart returns cold sync when onboarding is incomplete and stage1 never finished`() {
        val route = resolver.resolve(
            ColdSyncStartupSnapshot(
                onboardingCompleted = false,
                stage1CompletedAt = null,
                stage2CompletedAt = null,
                stage2Deferred = false,
            ),
        )

        assertEquals(ColdSyncStartupRoute.SHOW_COLD_SYNC, route)
    }

    @Test
    fun `COLD-007 restart resumes today with stage2 banner when onboarding is complete and stage2 is pending`() {
        val route = resolver.resolve(
            ColdSyncStartupSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = 1_000L,
                stage2CompletedAt = null,
                stage2Deferred = false,
            ),
        )

        assertEquals(ColdSyncStartupRoute.SHOW_TODAY_WITH_STAGE2_RESUME_BANNER, route)
    }

    @Test
    fun `COLD-007 restart returns today idle when stage2 was deferred`() {
        val route = resolver.resolve(
            ColdSyncStartupSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = 1_000L,
                stage2CompletedAt = null,
                stage2Deferred = true,
            ),
        )

        assertEquals(ColdSyncStartupRoute.SHOW_TODAY_IDLE, route)
    }

    @Test
    fun `COLD-007 restart returns today idle when stage2 already completed`() {
        val route = resolver.resolve(
            ColdSyncStartupSnapshot(
                onboardingCompleted = true,
                stage1CompletedAt = 1_000L,
                stage2CompletedAt = 2_000L,
                stage2Deferred = false,
            ),
        )

        assertEquals(ColdSyncStartupRoute.SHOW_TODAY_IDLE, route)
    }
}
