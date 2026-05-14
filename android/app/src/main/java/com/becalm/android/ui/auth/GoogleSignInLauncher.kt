package com.becalm.android.ui.auth

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.becalm.android.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Outcome of a single Google sign-in attempt.
 *
 * Callers must handle every branch explicitly — `sealed` prevents an accidental
 * `else ->` swallow of [UserCancelled] vs. [NoCredentials] (rubric B3).
 */
public sealed class GoogleSignInResult {
    /** A non-empty OIDC id-token was returned; forward it to Supabase. */
    public data class Success(val idToken: String) : GoogleSignInResult()
    /** The user dismissed the Credential Manager bottom sheet without selecting an account. */
    public data object UserCancelled : GoogleSignInResult()
    /** No matching Google credential exists on the device (for example, no Google account added). */
    public data object NoCredentials : GoogleSignInResult()
    /** Any other failure; callers should surface a generic error message. */
    public data class Error(val throwable: Throwable) : GoogleSignInResult()
}

/**
 * Handle returned by [rememberGoogleSignInLauncher] that exposes whether the launcher
 * is configured and a [launch] method that kicks off the Credential Manager bottom
 * sheet against [BuildConfig.GOOGLE_WEB_CLIENT_ID].
 *
 * The handle intentionally does NOT expose the underlying coroutine scope or
 * [CredentialManager] — callers should drive onboarding UI through [launch] only so
 * the PII and error-handling invariants below stay centralised.
 */
public class GoogleSignInHandle internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val credentialManager: CredentialManager,
    private val serverClientId: String,
    private val onResult: (GoogleSignInResult) -> Unit,
) {
    /** `true` when [BuildConfig.GOOGLE_WEB_CLIENT_ID] is configured; gate the CTA on this value. */
    public val isConfigured: Boolean = serverClientId.isNotBlank()

    /**
     * Opens the Credential Manager account picker; results are delivered asynchronously
     * to the `onResult` callback passed at [rememberGoogleSignInLauncher] time.
     *
     * No-op when [isConfigured] is `false` — the launcher refuses to call into
     * [CredentialManager] with an empty server client id because that produces an
     * unrecoverable error that looks to the user like a random crash.
     */
    @SuppressLint("CredentialManagerSignInWithGoogle")
    public fun launch() {
        if (!isConfigured) return
        scope.launch {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result: GoogleSignInResult = try {
                val response = credentialManager.getCredential(context = context, request = request)
                val credential = response.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    if (idToken.isNotBlank()) {
                        GoogleSignInResult.Success(idToken)
                    } else {
                        GoogleSignInResult.Error(IllegalStateException("blank id token"))
                    }
                } else {
                    GoogleSignInResult.Error(
                        IllegalStateException("unexpected credential type: ${credential.type}"),
                    )
                }
            } catch (_: GetCredentialCancellationException) {
                GoogleSignInResult.UserCancelled
            } catch (_: NoCredentialException) {
                GoogleSignInResult.NoCredentials
            } catch (t: GetCredentialException) {
                GoogleSignInResult.Error(t)
            }
            onResult(result)
        }
    }
}

/**
 * Composable binding around [CredentialManager] that exposes a [GoogleSignInHandle]
 * stable across recomposition.
 *
 * ## Why [CredentialManager] and not `AuthorizationClient`?
 * Supabase sign-in needs an OIDC id-token; `AuthorizationClient` issues OAuth
 * access-tokens and is the right choice for requesting Gmail scopes (see S6-F).
 * The two APIs coexist — this launcher is dedicated to AUTH-002 and carries no
 * Gmail scope at all.
 *
 * @param onResult Callback invoked on the main dispatcher with every launcher outcome.
 */
@Composable
public fun rememberGoogleSignInLauncher(
    onResult: (GoogleSignInResult) -> Unit,
): GoogleSignInHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    return remember(context, scope, credentialManager, serverClientId) {
        GoogleSignInHandle(
            scope = scope,
            context = context,
            credentialManager = credentialManager,
            serverClientId = serverClientId,
            onResult = onResult,
        )
    }
}
