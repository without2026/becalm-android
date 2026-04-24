package com.becalm.android.ui.today

/**
 * Startup snapshot used to decide whether the app should re-enter Cold Sync or resume Today.
 *
 * Owner seam for COLD-007 process-restart routing.
 */
public data class ColdSyncStartupSnapshot(
    val onboardingCompleted: Boolean,
    val stage1CompletedAt: Long?,
    val stage2CompletedAt: Long?,
    val stage2Deferred: Boolean,
)

/** Cold-sync-aware startup route projection. */
public enum class ColdSyncStartupRoute {
    SHOW_COLD_SYNC,
    SHOW_TODAY_WITH_STAGE2_RESUME_BANNER,
    SHOW_TODAY_IDLE,
}

/** Pure resolver for COLD-007 restart routing. */
public class ColdSyncRouteResolver {

    public fun resolve(snapshot: ColdSyncStartupSnapshot): ColdSyncStartupRoute = when {
        !snapshot.onboardingCompleted && snapshot.stage1CompletedAt == null -> {
            ColdSyncStartupRoute.SHOW_COLD_SYNC
        }
        snapshot.onboardingCompleted &&
            snapshot.stage2CompletedAt == null &&
            !snapshot.stage2Deferred -> {
            ColdSyncStartupRoute.SHOW_TODAY_WITH_STAGE2_RESUME_BANNER
        }
        else -> ColdSyncStartupRoute.SHOW_TODAY_IDLE
    }
}
