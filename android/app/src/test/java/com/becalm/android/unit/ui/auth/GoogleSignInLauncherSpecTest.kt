package com.becalm.android.unit.ui.auth

import android.app.PendingIntent
import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.ui.auth.GoogleSignInHandle
import com.becalm.android.ui.auth.GoogleSignInResult
import java.util.concurrent.Executor
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GoogleSignInLauncherSpecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `google sign-in launcher is no-op when web client id is blank`() = runTest {
        val credentialManager = RecordingCredentialManager(
            result = GetCredentialCancellationException(),
        )
        val results = mutableListOf<GoogleSignInResult>()

        val handle = GoogleSignInHandle(
            scope = this,
            context = context,
            credentialManager = credentialManager,
            serverClientId = "",
            onResult = results::add,
        )

        handle.launch()
        advanceUntilIdle()

        assertEquals(false, handle.isConfigured)
        assertEquals(0, credentialManager.getCredentialCalls)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `google sign-in launcher maps user cancellation distinctly`() = runTest {
        val result = launchWith(GetCredentialCancellationException())

        assertTrue(result is GoogleSignInResult.UserCancelled)
    }

    @Test
    fun `google sign-in launcher maps missing device credentials distinctly`() = runTest {
        val result = launchWith(NoCredentialException())

        assertTrue(result is GoogleSignInResult.NoCredentials)
    }

    @Test
    fun `google sign-in launcher maps generic credential failure to recoverable error`() = runTest {
        val result = launchWith(GetCredentialUnknownException())

        assertTrue(result is GoogleSignInResult.Error)
    }

    private suspend fun TestScope.launchWith(
        exception: GetCredentialException,
    ): GoogleSignInResult {
        val credentialManager = RecordingCredentialManager(result = exception)
        val results = mutableListOf<GoogleSignInResult>()
        val handle = GoogleSignInHandle(
            scope = this,
            context = context,
            credentialManager = credentialManager,
            serverClientId = "web-client-id",
            onResult = results::add,
        )

        handle.launch()
        advanceUntilIdle()

        assertEquals(true, handle.isConfigured)
        assertEquals(1, credentialManager.getCredentialCalls)
        return results.single()
    }

    private class RecordingCredentialManager(
        private val result: GetCredentialException,
    ) : CredentialManager {
        var getCredentialCalls = 0
            private set

        override fun getCredentialAsync(
            context: Context,
            request: GetCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
        ) {
            getCredentialCalls += 1
            executor.execute { callback.onError(result) }
        }

        override fun getCredentialAsync(
            context: Context,
            pendingGetCredentialHandle: PrepareGetCredentialResponse.PendingGetCredentialHandle,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
        ) = error("not used")

        override fun prepareGetCredentialAsync(
            request: GetCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<PrepareGetCredentialResponse, GetCredentialException>,
        ) = error("not used")

        override fun createCredentialAsync(
            context: Context,
            request: CreateCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>,
        ) = error("not used")

        override fun clearCredentialStateAsync(
            request: ClearCredentialStateRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<Void?, ClearCredentialException>,
        ) = error("not used")

        override fun createSettingsPendingIntent(): PendingIntent = error("not used")
    }
}
