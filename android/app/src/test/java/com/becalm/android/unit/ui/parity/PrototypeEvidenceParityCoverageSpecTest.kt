package com.becalm.android.unit.ui.parity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrototypeEvidenceParityCoverageSpecTest {
    @Test
    fun `all prototype evidence screenshots have Android parity capture targets`() {
        val source = parityScreenshotTestSource()
        val evidenceNames = prototypeEvidenceCoverage.map { it.evidence }.distinct()

        assertEquals(expectedPrototypeEvidenceNames, evidenceNames)
        prototypeEvidenceCoverage.forEach { coverage ->
            assertTrue(
                "Missing capture method ${coverage.captureMethod} for ${coverage.evidence}",
                source.contains("fun ${coverage.captureMethod}("),
            )
            assertTrue(
                "Missing screenshot target ${coverage.targetFile} for ${coverage.evidence}",
                source.contains("captureScreenshot(\"${coverage.targetFile}\""),
            )
        }
    }

    private fun parityScreenshotTestSource(): String {
        val candidates = listOf(
            File("src/androidTest/java/com/becalm/android/ui/parity/HtmlPrototypeParityScreenshotTest.kt"),
            File("app/src/androidTest/java/com/becalm/android/ui/parity/HtmlPrototypeParityScreenshotTest.kt"),
        )
        val sourceFile = candidates.firstOrNull(File::isFile)
        if (sourceFile != null) {
            return sourceFile.readText()
        }
        fail("HtmlPrototypeParityScreenshotTest.kt was not found from ${File(".").absolutePath}")
        throw AssertionError("unreachable")
    }

    private data class EvidenceCoverage(
        val evidence: String,
        val targetFile: String,
        val captureMethod: String,
    )

    private companion object {
        private val expectedPrototypeEvidenceNames = listOf(
            "01-initial.png",
            "02-onboarding-identity.png",
            "03-onboarding-people-connected.png",
            "04-onboarding-calendar-connected.png",
            "05-onboarding-mail-done.png",
            "06-onboarding-first-aha.png",
            "07-person-search-tax.png",
            "08-person-kim-detail.png",
            "09-person-kim-evidence-sheet.png",
            "10-person-kim-compose-sheet.png",
            "11-person-choi-recall.png",
            "12-person-choi-compose-sheet.png",
            "13-schedule-diff-tab.png",
            "14-schedule-evidence-sheet.png",
            "15-commitment-give-take.png",
            "16-sync-reconnect-toast.png",
        )

        private val prototypeEvidenceCoverage = listOf(
            EvidenceCoverage(
                evidence = "01-initial.png",
                targetFile = "onboarding-welcome-privacy-note.png",
                captureMethod = "captureOnboardingWelcomePrivacyNote",
            ),
            EvidenceCoverage(
                evidence = "01-initial.png",
                targetFile = "persons-action-first-source-status.png",
                captureMethod = "capturePersonsActionFirstListWithSourceStatus",
            ),
            EvidenceCoverage(
                evidence = "02-onboarding-identity.png",
                targetFile = "onboarding-identity-input.png",
                captureMethod = "captureOnboardingIdentityInputStep",
            ),
            EvidenceCoverage(
                evidence = "03-onboarding-people-connected.png",
                targetFile = "onboarding-people-connected.png",
                captureMethod = "captureOnboardingPeopleConnectedStep",
            ),
            EvidenceCoverage(
                evidence = "04-onboarding-calendar-connected.png",
                targetFile = "onboarding-calendar-connected.png",
                captureMethod = "captureOnboardingCalendarConnectedStep",
            ),
            EvidenceCoverage(
                evidence = "05-onboarding-mail-done.png",
                targetFile = "onboarding-mail-connected.png",
                captureMethod = "captureOnboardingMailConnectedStep",
            ),
            EvidenceCoverage(
                evidence = "06-onboarding-first-aha.png",
                targetFile = "onboarding-gmail-activation-ready.png",
                captureMethod = "captureOnboardingGmailActivationReady",
            ),
            EvidenceCoverage(
                evidence = "06-onboarding-first-aha.png",
                targetFile = "onboarding-gmail-activation-loading.png",
                captureMethod = "captureOnboardingGmailActivationLoading",
            ),
            EvidenceCoverage(
                evidence = "07-person-search-tax.png",
                targetFile = "persons-role-search-tax.png",
                captureMethod = "capturePersonsRoleSearchTaxResult",
            ),
            EvidenceCoverage(
                evidence = "07-person-search-tax.png",
                targetFile = "persons-role-search-tax-source-status.png",
                captureMethod = "capturePersonsRoleSearchTaxResultWithSourceStatus",
            ),
            EvidenceCoverage(
                evidence = "08-person-kim-detail.png",
                targetFile = "person-detail-primary-action-density.png",
                captureMethod = "capturePersonDetailPrimaryActionDensity",
            ),
            EvidenceCoverage(
                evidence = "09-person-kim-evidence-sheet.png",
                targetFile = "person-action-evidence-sheet.png",
                captureMethod = "capturePersonActionEvidenceWhyAndOriginalSheet",
            ),
            EvidenceCoverage(
                evidence = "09-person-kim-evidence-sheet.png",
                targetFile = "raw-event-detail-original-context.png",
                captureMethod = "captureRawEventDetailOriginalContext",
            ),
            EvidenceCoverage(
                evidence = "10-person-kim-compose-sheet.png",
                targetFile = "person-action-draft-reply-sheet.png",
                captureMethod = "capturePersonActionDraftReplySheet",
            ),
            EvidenceCoverage(
                evidence = "11-person-choi-recall.png",
                targetFile = "person-detail-stale-recall.png",
                captureMethod = "capturePersonDetailStaleRelationshipRecall",
            ),
            EvidenceCoverage(
                evidence = "12-person-choi-compose-sheet.png",
                targetFile = "person-reconnect-draft-sheet.png",
                captureMethod = "capturePersonReconnectDraftSheet",
            ),
            EvidenceCoverage(
                evidence = "12-person-choi-compose-sheet.png",
                targetFile = "person-action-draft-reply-sheet.png",
                captureMethod = "capturePersonActionDraftReplySheet",
            ),
            EvidenceCoverage(
                evidence = "13-schedule-diff-tab.png",
                targetFile = "schedule-missing-calendar-actions.png",
                captureMethod = "captureScheduleMissingCalendarActions",
            ),
            EvidenceCoverage(
                evidence = "13-schedule-diff-tab.png",
                targetFile = "schedule-candidates-conflict-review.png",
                captureMethod = "captureScheduleCandidatesAndConflictReview",
            ),
            EvidenceCoverage(
                evidence = "14-schedule-evidence-sheet.png",
                targetFile = "schedule-action-evidence-sheet.png",
                captureMethod = "captureScheduleActionEvidenceWhyAndOriginalSheet",
            ),
            EvidenceCoverage(
                evidence = "14-schedule-evidence-sheet.png",
                targetFile = "commitment-detail-source-evidence-sheet.png",
                captureMethod = "captureCommitmentDetailSourceEvidenceSheet",
            ),
            EvidenceCoverage(
                evidence = "15-commitment-give-take.png",
                targetFile = "commitments-open-give-take.png",
                captureMethod = "captureCommitmentsOpenGiveTakeActions",
            ),
            EvidenceCoverage(
                evidence = "16-sync-reconnect-toast.png",
                targetFile = "persons-source-processing-status.png",
                captureMethod = "capturePersonsSourceAndProcessingWarnings",
            ),
            EvidenceCoverage(
                evidence = "16-sync-reconnect-toast.png",
                targetFile = "persons-multiple-source-status.png",
                captureMethod = "capturePersonsMultipleSourceWarnings",
            ),
            EvidenceCoverage(
                evidence = "16-sync-reconnect-toast.png",
                targetFile = "commitments-open-source-status.png",
                captureMethod = "captureCommitmentsOpenActionsWithSourceStatus",
            ),
            EvidenceCoverage(
                evidence = "16-sync-reconnect-toast.png",
                targetFile = "commitments-multiple-source-status.png",
                captureMethod = "captureCommitmentsOpenActionsWithMultipleSourceWarnings",
            ),
        )
    }
}
