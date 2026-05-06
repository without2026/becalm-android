package com.becalm.android.ui.onboarding

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
internal fun rememberRecordingFolderDetection(
    override: RecordingFolderDetection? = null,
): State<RecordingFolderDetection> =
    produceState(
        initialValue = override ?: RecordingFolderDetector.fallback(),
        key1 = override,
    ) {
        if (override != null) {
            value = override
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            RecordingFolderDetector.detect { path -> File(path).exists() }
        }
    }
