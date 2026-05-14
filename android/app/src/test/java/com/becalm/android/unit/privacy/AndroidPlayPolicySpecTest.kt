package com.becalm.android.unit.privacy

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlayPolicySpecTest {

    @Test
    fun `battery optimization onboarding uses app settings instead of restricted exemption request`() {
        // spec: ONB-005
        val manifest = repoFile("android/app/src/main/AndroidManifest.xml").readText()
        val screen = repoFile(
            "android/app/src/main/java/com/becalm/android/ui/onboarding/BatteryOptimizationScreen.kt",
        ).readText()

        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(screen.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(screen.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
    }

    private fun repoFile(path: String): File {
        val fromAppDir = File("../../$path")
        if (fromAppDir.exists()) return fromAppDir
        val fromAndroidDir = File("../$path")
        if (fromAndroidDir.exists()) return fromAndroidDir
        return File(path)
    }
}
