package com.syncit.app.auth

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException

/**
 * "Continue with Google" through Credential Manager. Returns a Google ID token; AuthService turns
 * it into a Firebase sign-in, so this is the only place that touches Google's UI APIs.
 */
object GoogleSignIn {

    sealed interface Result {
        data class Token(val idToken: String) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    /**
     * The OAuth web client ID Firebase needs, from the string the google-services plugin generates
     * (`default_web_client_id`). It only exists once the Google provider is enabled in the Firebase
     * console and google-services.json is downloaded again, so it's looked up by name: the build
     * works without it, and the button says sign-in isn't set up yet. Kept from resource shrinking
     * by res/raw/keep.xml.
     */
    // Resource lookup by name is deliberate: a compile-time R.string reference would fail the build
    // until the Google provider is enabled.
    @SuppressLint("DiscouragedApi")
    fun serverClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) return null
        return context.getString(id).takeIf { it.isNotBlank() }
    }

    /** [activityContext] must be an Activity: Credential Manager shows its sheet over it. */
    suspend fun requestIdToken(activityContext: Context, serverClientId: String): Result {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Result.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.Failed("Google didn't return an account")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            Result.Cancelled
        } catch (_: NoCredentialException) {
            Result.Failed("No Google account found on this phone. Add one in Settings, or continue with email.")
        } catch (e: GetCredentialException) {
            Result.Failed(e.errorMessage?.toString() ?: "Google sign-in didn't work. Try again or continue with email.")
        } catch (_: GoogleIdTokenParsingException) {
            Result.Failed("Google sign-in didn't work. Try again or continue with email.")
        }
    }
}
