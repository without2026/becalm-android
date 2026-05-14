package com.becalm.android.unit.pipeline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessQaScriptSpecTest {

    @Test
    fun `readiness measurement fails on missing or over-threshold metrics by default`() {
        // spec: REL-010
        val script = repoFile("qa/emulator/scripts/measure_android_readiness.sh").readText()

        assertTrue(script.contains("STRICT=\"\${BECALM_READINESS_STRICT:-1}\""))
        assertTrue(script.contains("MAX_COLD_START_MS=\"\${BECALM_MAX_COLD_START_MS:-3000}\""))
        assertTrue(script.contains("MAX_TOTAL_PSS_KB=\"\${BECALM_MAX_TOTAL_PSS_KB:-262144}\""))
        assertTrue(script.contains("cold_start_threshold \"FAIL unavailable\""))
        assertTrue(script.contains("memory_threshold \"FAIL unavailable\""))
        assertTrue(script.contains("record_failure \"cold_start_total_ms="))
        assertTrue(script.contains("record_failure \"total_pss_kb="))
        assertTrue(script.contains("if [[ \"${'$'}STRICT\" != \"0\" && \"${'$'}FAILURES\" -gt 0 ]]"))
    }

    @Test
    fun `readiness measurement scans logcat for app fatal anr and oom signals`() {
        // spec: REL-010
        val script = repoFile("qa/emulator/scripts/measure_android_readiness.sh").readText()

        assertTrue(script.contains("run_adb logcat -c"))
        assertTrue(script.contains("run_adb logcat -d -v time"))
        assertTrue(script.contains("FATAL EXCEPTION"))
        assertTrue(script.contains("ANR in \${PACKAGE_NAME}"))
        assertTrue(script.contains("OutOfMemoryError"))
        assertTrue(script.contains("lowmemorykiller.*\${PACKAGE_NAME}"))
        assertTrue(script.contains("logcat_threshold \"FAIL fatal_or_anr_or_oom\""))
        assertTrue(script.contains("logcat_threshold \"PASS no fatal/ANR/OOM patterns\""))
    }

    private fun repoFile(path: String): File {
        val fromAppDir = File("../../$path")
        if (fromAppDir.exists()) return fromAppDir
        val fromAndroidDir = File("../$path")
        if (fromAndroidDir.exists()) return fromAndroidDir
        return File(path)
    }
}
