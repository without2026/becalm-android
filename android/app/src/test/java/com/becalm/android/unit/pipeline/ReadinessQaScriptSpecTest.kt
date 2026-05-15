package com.becalm.android.unit.pipeline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessQaScriptSpecTest {

    @Test
    fun `readiness measurement fails on missing or over-threshold metrics by default`() {
        // spec: REL-010
        val script = repoFile("qa/emulator/scripts/measure_android_readiness.sh").readText()

        assertTrue(script.contains("command -v adb"))
        assertTrue(script.contains("STRICT=\"\${BECALM_READINESS_STRICT:-1}\""))
        assertTrue(script.contains("APP_APK=\"\${BECALM_APK:-\$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk}\""))
        assertTrue(script.contains("pm path \"\$PACKAGE_NAME\""))
        assertTrue(script.contains("run_adb install -r \"\$APP_APK\""))
        assertTrue(script.contains("MAX_COLD_START_MS=\"\${BECALM_MAX_COLD_START_MS:-3000}\""))
        assertTrue(script.contains("MAX_TOTAL_PSS_KB=\"\${BECALM_MAX_TOTAL_PSS_KB:-262144}\""))
        assertTrue(script.contains("MAX_SKIPPED_FRAMES=\"\${BECALM_MAX_SKIPPED_FRAMES:-60}\""))
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

    @Test
    fun `readiness measurement fails on excessive app skipped frames`() {
        // spec: REL-010
        val script = repoFile("qa/emulator/scripts/measure_android_readiness.sh").readText()

        assertTrue(script.contains("APP_PID=\"\$(run_adb shell pidof \"\$PACKAGE_NAME\""))
        assertTrue(script.contains("metric app_pid \"\${APP_PID:-unknown}\""))
        assertTrue(script.contains("Choreographer\\\\([[:space:]]*\${APP_PID}\\\\): Skipped [0-9]+ frames"))
        assertTrue(script.contains("metric skipped_frames_max \"\$SKIPPED_FRAMES_MAX\""))
        assertTrue(script.contains("skipped_frames_threshold \"FAIL over \${MAX_SKIPPED_FRAMES} frames\""))
        assertTrue(script.contains("record_failure \"skipped_frames_max="))
        assertTrue(script.contains("skipped_frames_threshold \"PASS <=\${MAX_SKIPPED_FRAMES} frames\""))
    }

    private fun repoFile(path: String): File {
        val fromAppDir = File("../../$path")
        if (fromAppDir.exists()) return fromAppDir
        val fromAndroidDir = File("../$path")
        if (fromAndroidDir.exists()) return fromAndroidDir
        return File(path)
    }
}
