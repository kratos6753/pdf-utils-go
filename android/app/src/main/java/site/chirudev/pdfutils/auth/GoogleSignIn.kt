package site.chirudev.pdfutils.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Returns a Google ID token for the backend's /v1/auth/google/token, or null if cancelled. */
suspend fun requestGoogleIdToken(activityContext: Context, serverClientId: String): Result<String?> = runCatching {
    val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    try {
        val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else {
            error("Unexpected credential type")
        }
    } catch (_: GetCredentialCancellationException) {
        null
    } catch (e: NoCredentialException) {
        throw IllegalStateException("Add a Google account to this device to continue with Google", e)
    } catch (e: GetCredentialException) {
        throw IllegalStateException("Google sign-in isn't available right now", e)
    }
}
