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
        assertTrue(workflow.contains("./gradlew verifyReleaseRuntimeConfigured verifyReleaseSigningConfigured"))
        assertTrue(workflow.contains("verifyReleaseSigningConfigured"))
        assertTrue(workflow.contains("./gradlew bundleRelease"))
        assertTrue(workflow.contains("name: android-aab"))
        assertTrue(workflow.contains("app/build/outputs/bundle/**/*.aab"))
        assertTrue(workflow.contains("inputs.adapter != 'android'"))
        assertFalse(workflow.contains("BeCalmv3"))
    }

    @Test
    fun `android adapter build template uses project root not legacy paths`() {
        // spec: REL-001
        val workflow = repoFile(".pipeline/adapters/android/build.yml").readText()

        assertTrue(workflow.contains("Read project_root"))
        assertTrue(workflow.contains("if [ \"\${{ inputs.require_signing }}\" = \"true\" ]; then"))
        assertTrue(workflow.contains("./gradlew verifyReleaseRuntimeConfigured verifyReleaseSigningConfigured"))
        assertTrue(workflow.contains("verifyReleaseSigningConfigured"))
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

    @Test
    fun `protected android release verifies runtime configuration before bundle`() {
        // spec: REL-004
        val buildFile = repoFile("android/app/build.gradle.kts").readText()
        val workflow = repoFile(".github/workflows/adapter-build.yml").readText()
        val adapterTemplate = repoFile(".pipeline/adapters/android/build.yml").readText()

        assertTrue(buildFile.contains("verifyReleaseRuntimeConfigured"))
        assertTrue(buildFile.contains("BECALM_API_BASE_URL"))
        assertTrue(buildFile.contains("SUPABASE_URL"))
        assertTrue(buildFile.contains("SUPABASE_ANON_KEY"))
        assertTrue(buildFile.contains("GOOGLE_WEB_CLIENT_ID"))
        assertTrue(workflow.contains("./gradlew verifyReleaseRuntimeConfigured verifyReleaseSigningConfigured"))
        assertTrue(adapterTemplate.contains("./gradlew verifyReleaseRuntimeConfigured verifyReleaseSigningConfigured"))
    }

    @Test
    fun `production deploy wires android aab to play console`() {
        // spec: REL-005
        val workflow = repoFile(".github/workflows/deploy-production.yml").readText()

        assertTrue(workflow.contains("play_console_track:"))
        assertTrue(workflow.contains("build-android:"))
        assertTrue(workflow.contains("deploy-android:"))
        assertTrue(workflow.contains("adapter: android"))
        assertTrue(workflow.contains("require_signing: true"))
        assertTrue(workflow.contains("name: android-aab"))
        assertTrue(workflow.contains("r0adkll/upload-google-play@v1"))
        assertTrue(workflow.contains("packageName: com.becalm.android"))
        assertTrue(workflow.contains("track: \${{ needs.load-config.outputs.play_console_track }}"))
    }

    @Test
    fun `staging deploy wires android aab to firebase app distribution`() {
        // spec: REL-006
        val workflow = repoFile(".github/workflows/deploy-staging.yml").readText()

        assertTrue(workflow.contains("adapter:"))
        assertTrue(workflow.contains("staging_mechanism:"))
        assertTrue(workflow.contains("android-staging-preflight:"))
        assertTrue(workflow.contains("distribution_enabled:"))
        assertTrue(workflow.contains("needs.android-staging-preflight.outputs.distribution_enabled == 'true'"))
        assertTrue(workflow.contains("build-android:"))
        assertTrue(workflow.contains("deploy-android-staging:"))
        assertTrue(workflow.contains("adapter: android"))
        assertTrue(workflow.contains("require_signing: true"))
        assertTrue(workflow.contains("name: android-aab"))
        assertTrue(workflow.contains("wzieba/Firebase-Distribution-Github-Action@v1"))
        assertTrue(workflow.contains("FIREBASE_APP_ID"))
        assertTrue(workflow.contains("FIREBASE_SERVICE_ACCOUNT_JSON"))
        assertTrue(workflow.contains("FIREBASE_TESTER_GROUPS"))
    }

    @Test
    fun `android adapter dispatchers do not reference missing platform workflows`() {
        // spec: REL-007
        val build = repoFile(".github/workflows/adapter-build.yml").readText()
        val gates = repoFile(".github/workflows/adapter-gates.yml").readText()
        val tests = repoFile(".github/workflows/adapter-tests.yml").readText()

        val combined = "$build\n$gates\n$tests"
        assertFalse(combined.contains("electron-build.yml"))
        assertFalse(combined.contains("electron-gates.yml"))
        assertFalse(combined.contains("electron-tests.yml"))
        assertFalse(combined.contains("web-gates.yml"))
        assertFalse(combined.contains("web-tests.yml"))
        assertTrue(build.contains("inputs.adapter != 'android'"))
        assertTrue(gates.contains("inputs.adapter != 'android'"))
        assertTrue(tests.contains("inputs.adapter != 'android'"))
    }

    @Test
    fun `workflows do not interpolate untrusted pr branch names in shell scripts`() {
        // spec: REL-008
        val workflow = repoFile(".github/workflows/ci-scenario-gen.yml").readText()

        assertFalse(workflow.contains("HEAD:\${{ github.head_ref }}"))
        assertTrue(workflow.contains("PR_HEAD_REF: \${{ github.head_ref }}"))
        assertTrue(workflow.contains("git push origin \"HEAD:\$PR_HEAD_REF\""))
    }

    @Test
    fun `github workflows and android adapter templates use node twenty four compatible majors`() {
        // spec: REL-009
        val workflows = repoFile(".github/workflows").walkTopDown()
            .filter { it.isFile && it.extension == "yml" }
            .joinToString("\n") { it.readText() }
        val androidAdapterTemplates = repoFile(".pipeline/adapters/android").walkTopDown()
            .filter { it.isFile && it.extension == "yml" }
            .joinToString("\n") { it.readText() }
        val combined = "$workflows\n$androidAdapterTemplates"

        assertFalse(combined.contains("actions/checkout@v4"))
        assertFalse(combined.contains("actions/setup-java@v4"))
        assertFalse(combined.contains("actions/setup-python@v5"))
        assertFalse(combined.contains("actions/upload-artifact@v4"))
        assertFalse(combined.contains("actions/download-artifact@v4"))
        assertFalse(combined.contains("actions/cache@v4"))
        assertTrue(combined.contains("actions/checkout@v6"))
        assertTrue(combined.contains("actions/setup-java@v5"))
        assertTrue(combined.contains("actions/setup-python@v6"))
        assertTrue(combined.contains("actions/upload-artifact@v7"))
        assertTrue(combined.contains("actions/download-artifact@v8"))
        assertTrue(combined.contains("actions/cache@v5"))
    }

    @Test
    fun `android release verification workflows can be manually dispatched`() {
        // spec: REL-010
        val tests = repoFile(".github/workflows/android-tests.yml").readText()
        val gates = repoFile(".github/workflows/android-gates.yml").readText()

        assertTrue(tests.contains("workflow_call:"))
        assertTrue(tests.contains("workflow_dispatch:"))
        assertTrue(tests.contains("./gradlew connectedDebugAndroidTest"))
        assertTrue(gates.contains("workflow_call:"))
        assertTrue(gates.contains("workflow_dispatch:"))
        assertTrue(gates.contains("./gradlew dependencyCheckAnalyze"))
        assertTrue(gates.contains("NVD_API_KEY: ${'$'}{{ secrets.NVD_API_KEY }}"))
        assertTrue(gates.contains("if: ${'$'}{{ env.NVD_API_KEY != '' }}"))
        assertTrue(gates.contains("if: ${'$'}{{ env.NVD_API_KEY == '' }}"))
        assertTrue(gates.contains("./gradlew lint"))
    }

    @Test
    fun `android verification workflows bound runtime and preserve failure reports`() {
        // spec: REL-010
        val tests = repoFile(".github/workflows/android-tests.yml").readText()
        val gates = repoFile(".github/workflows/android-gates.yml").readText()
        val combined = "$tests\n$gates"

        assertTrue(tests.contains("timeout-minutes: 15"))
        assertTrue(tests.contains("timeout-minutes: 25"))
        assertTrue(tests.contains("timeout-minutes: 30"))
        assertTrue(gates.contains("timeout-minutes: 20"))
        assertTrue(combined.contains("if: always()"))
        assertTrue(combined.contains("android-unit-test-reports"))
        assertTrue(combined.contains("android-release-smoke-reports"))
        assertTrue(combined.contains("android-instrumented-test-reports"))
        assertTrue(combined.contains("android-gate-reports"))
        assertTrue(combined.contains("app/build/reports/androidTests/connected"))
    }

    @Test
    fun `android gate adapter template mirrors dependency check secret fallback`() {
        // spec: REL-010
        val gates = repoFile(".pipeline/adapters/android/gates.yml").readText()

        assertTrue(gates.contains("NVD_API_KEY: ${'$'}{{ secrets.NVD_API_KEY }}"))
        assertTrue(gates.contains("if: ${'$'}{{ env.NVD_API_KEY != '' }}"))
        assertTrue(gates.contains("if: ${'$'}{{ env.NVD_API_KEY == '' }}"))
        assertTrue(gates.contains("./gradlew tasks --all --console=plain | grep -q \"dependencyCheckAnalyze\""))
    }

    @Test
    fun `android adapter verification templates bound runtime and preserve failure reports`() {
        // spec: REL-010
        val tests = repoFile(".pipeline/adapters/android/test.yml").readText()
        val gates = repoFile(".pipeline/adapters/android/gates.yml").readText()
        val combined = "$tests\n$gates"

        assertTrue(tests.contains("timeout-minutes: 15"))
        assertTrue(tests.contains("timeout-minutes: 25"))
        assertTrue(tests.contains("timeout-minutes: 30"))
        assertTrue(gates.contains("timeout-minutes: 20"))
        assertTrue(combined.contains("if: always()"))
        assertTrue(combined.contains("android-unit-test-reports"))
        assertTrue(combined.contains("android-release-smoke-reports"))
        assertTrue(combined.contains("android-instrumented-test-reports"))
        assertTrue(combined.contains("android-gate-reports"))
        assertTrue(combined.contains("app/build/reports/androidTests/connected"))
    }

    private fun repoFile(path: String): File {
        val fromAppDir = File("../../$path")
        if (fromAppDir.exists()) return fromAppDir
        val fromAndroidDir = File("../$path")
        if (fromAndroidDir.exists()) return fromAndroidDir
        return File(path)
    }
}
