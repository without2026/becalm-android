package com.becalm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Single-activity entry point for the BeCalm Android app.
 *
 * Enables edge-to-edge display and delegates all UI to [BecalmApp].
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the launch-time splash theme for the real app theme BEFORE super.onCreate
        // so the first Compose frame renders against Theme.Becalm, not Theme.Becalm.Splash.
        setTheme(R.style.Theme_Becalm)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BecalmTheme {
                BecalmApp()
            }
        }
    }
}
