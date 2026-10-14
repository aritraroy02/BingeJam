package com.syncit.core.auth

import com.syncit.core.model.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Accounts: Google sign-in or a passwordless email link (user's choice, 2026-10-15; both free on
 * Firebase). Rooms, friends and chat all require a signed-in user. Implemented in :data-firebase.
 *
 * Google: the :app module gets a Google ID token from Credential Manager and passes it here, so
 * this contract stays free of Android UI types.
 */
interface AuthService {
    val state: StateFlow<AuthState>

    /** Firebase uid of the signed-in user, or null. Same value the transport and repositories use. */
    val currentUid: String?

    suspend fun signInWithGoogleIdToken(idToken: String)

    /**
     * Emails a one-tap sign-in link to [email]. The link opens the app (App Link on the project's
     * hosting domain); the app then calls [completeEmailLinkSignIn]. The address is remembered
     * on-device so the user doesn't retype it.
     */
    suspend fun sendEmailSignInLink(email: String)

    fun isEmailSignInLink(link: String): Boolean

    /** [email] may be null when the link is opened on the same device that requested it. */
    suspend fun completeEmailLinkSignIn(link: String, email: String?)

    /** The address a link was last sent to from this device, if any. */
    val pendingEmail: String?

    suspend fun signOut()
}

class EmailRequiredException : Exception("Enter the email address the link was sent to")
