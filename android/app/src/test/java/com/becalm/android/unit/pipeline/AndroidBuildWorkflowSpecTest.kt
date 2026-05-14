package com.becalm.android.unit.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBuildWorkflowSpecTest {

    @Test
    fun `adapter build supports android release aab artifact`() {
        // spec: REL-001
        val workflow = repoFile(".github/workflows/adapter-build.yml").readText()

        assertTrue(workflow.contains("android-build:"))
        assertTrue(workflow.contains("if: inputs.adapter == 'android'"))
        assertTrue(workflow.contains("if [ \"\${{ inputs.require_signing }}\" = \"true\" ]; then"))
        assertTrue(workflow.contains("./gradlew verifyReleaseSigningConfigured"))
        assertTrue(workflow.contains("./gradlew bundleRelease"))
        assertTrue(workflow.contains("name: android-aab"))
        assertTrue(workflow.contains("app/build/outputs/bundle/**/*.aab"))
        assertTrue(workflow.contains("inputs.adapter != 'electron' && inputs.adapter != 'android'"))
        assertFalse(workflow.contains("BeCalmv3"))
    }

    @Test
    fun `android adapter build template uses project root not legacy paths`() {
        // spec: REL-001
        val workflow = repoFile(".pipeline/adapters/android/build.yml").readText()

        assertTrue(workflow.contains("Read project_root"))
        assertTrue(workflow.contains("if [ \"\${{ inputs.require_signing }}\" = \"true\" ]; then"))
        assertTrue(workflow.contains("./gradlew verifyReleaseSigningConfigured"))
        assertTrue(workflow.contains("./gradlew bundleRelease"))
        assertTrue(workflow.contains("name: android-aab"))
        assertTrue(workflow.contains("app/build/outputs/bundle/**/*.aab"))
        assertFalse(workflow.contains("BeCalmv3"))
    }

    @Test
    fun `android gradle exposes release signing verification task`() {
        // spec: REL-002
        val buildFile = repoFile("android/app/build.gradle.kts").readText()

        assertTrue(buildFile.contains("verifyReleaseSigningConfigured"))
        assertTrue(buildFile.contains("BECALM_RELEASE_STORE_FILE"))
        assertTrue(buildFile.contains("BECALM_RELEASE_STORE_PASSWORD"))
        assertTrue(buildFile.contains("BECALM_RELEASE_KEY_ALIAS"))
        assertTrue(buildFile.contains("BECALM_RELEASE_KEY_PASSWORD"))
        assertTrue(buildFile.contains("releaseUpload"))
    }

    private fun repoFile(path: String): File {
        val fromAppDir = File("../../$path")
        if (fromAppDir.exists()) return fromAppDir
        val fromAndroidDir = File("../$path")
        if (fromAndroidDir.exists()) return fromAndroidDir
        return File(path)
    }
}
