package com.becalm.android.unit.ui.auth

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CustomCredential
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
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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
            exception = GetCredentialCancellationException(),
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

    @Test
    fun `google sign-in launcher maps valid google credential to success`() = runTest {
        val result = launchWith(
            GetCredentialResponse(
                GoogleIdTokenCredential(
                    "google-account-id",
                    "id-token",
                    null,
                    null,
                    null,
                    null,
                    null,
                ),
            ),
        )

        assertEquals(GoogleSignInResult.Success("id-token"), result)
    }

    @Test
    fun `google sign-in launcher maps malformed google credential to recoverable error`() = runTest {
        val result = launchWith(
            GetCredentialResponse(
                CustomCredential(
                    GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
                    Bundle(),
                ),
            ),
        )

        assertTrue(result is GoogleSignInResult.Error)
    }

    @Test
    fun `google sign-in launcher maps unexpected credential type to recoverable error`() = runTest {
        val result = launchWith(
            GetCredentialResponse(
                CustomCredential("unexpected-credential-type", Bundle()),
            ),
        )

        assertTrue(result is GoogleSignInResult.Error)
    }

    private suspend fun TestScope.launchWith(
        exception: GetCredentialException,
    ): GoogleSignInResult = launchWith(RecordingCredentialManager(exception = exception))

    private suspend fun TestScope.launchWith(
        response: GetCredentialResponse,
    ): GoogleSignInResult = launchWith(RecordingCredentialManager(response = response))

    private suspend fun TestScope.launchWith(
        credentialManager: RecordingCredentialManager,
    ): GoogleSignInResult {
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
        private val response: GetCredentialResponse? = null,
        private val exception: GetCredentialException? = null,
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
            executor.execute {
                when {
                    response != null -> callback.onResult(response)
                    exception != null -> callback.onError(exception)
                    else -> error("response or exception required")
                }
            }
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
