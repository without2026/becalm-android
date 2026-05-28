package com.becalm.android.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
public class ShareImportActivity : ComponentActivity() {

    @Inject
    public lateinit var sourceImportRepository: SourceImportRepository

    @Inject
    public lateinit var logger: Logger

    private val uiState = mutableStateOf<ShareImportState>(ShareImportState.Importing)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Becalm)
        super.onCreate(savedInstanceState)
        render()
        importSharedImage(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedImage(intent)
    }

    private fun render() {
        setContent {
            BecalmTheme {
                ShareImportScreen(
                    state = uiState.value,
                    onOpenApp = { openMainApp(route = null) },
                    onClose = ::finish,
                )
            }
        }
    }

    private fun importSharedImage(intent: Intent?) {
        val uri = intent?.let(ShareImportIntents::singleImageUri)
        if (uri == null) {
            uiState.value = ShareImportState.Error
            return
        }
        uiState.value = ShareImportState.Importing
        lifecycleScope.launch {
            when (sourceImportRepository.importMessageScreenshot(uri)) {
                is BecalmResult.Success -> openMainApp(route = BecalmRoute.Today.path, importCompleted = true)
                is BecalmResult.Failure -> {
                    logger.w(TAG, "shared image import failed")
                    uiState.value = ShareImportState.Error
                }
            }
        }
    }

    private fun openMainApp(route: String?, importCompleted: Boolean = false) {
        startActivity(
            ShareImportNavigation.mainAppIntent(
                context = this,
                route = route,
                importCompleted = importCompleted,
            ),
        )
        finish()
    }

    private companion object {
        private const val TAG = "ShareImportActivity"
    }
}

private enum class ShareImportState {
    Importing,
    Error,
}

@Composable
private fun ShareImportScreen(
    state: ShareImportState,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                ShareImportState.Importing -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.share_import_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.share_import_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                ShareImportState.Error -> {
                    Text(
                        text = stringResource(R.string.share_import_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.share_import_error_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    BecalmButton(
                        text = stringResource(R.string.share_import_open_app),
                        onClick = onOpenApp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BecalmButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onClose,
                        variant = BecalmButtonVariant.Text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
