package com.becalm.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.becalm.android.ui.BeCalmApp
import dagger.hilt.android.AndroidEntryPoint

// spec: AUTH-001..AUTH-007 — entry point; NavHost decides auth vs onboarding vs main routing

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeCalmApp()
        }
    }
}
