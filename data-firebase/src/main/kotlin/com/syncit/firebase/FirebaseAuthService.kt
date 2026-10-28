package com.syncit.firebase

import android.content.Context
import android.util.Log
import android.util.Patterns
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.syncit.core.auth.AuthService
import com.syncit.core.auth.EmailRequiredException
import com.syncit.core.auth.RecentLoginRequiredException
import com.syncit.core.model.AuthState
import com.syncit.core.model.SignInProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AuthService] on Firebase Auth: Google (ID token from Credential Manager in :app) or a
 * passwordless e-mail link.
 *
 * E-mail links: Firebase Dynamic Links is shut down. ActionCodeSettings.setLinkDomain only accepts
 * a *custom* Hosting domain (the API rejects web.app/firebaseapp.com with INVALID_HOSTING_LINK_DOMAIN,
 * checked 2026-10-15), so we don't set it and Firebase uses the project's default handler on
 * [EMAIL_LINK_DOMAIN] (syncit-watchparty.firebaseapp.com, same Hosting site, whose assetlinks.json
 * lists the app). The e-mailed link is under https://syncit-watchparty.firebaseapp.com/__/auth/…
 * and Android opens it in the app through an App Link (host [EMAIL_LINK_DOMAIN], path prefix
 * [EMAIL_LINK_PATH_PREFIX]). Pass the whole URL to [isEmailSignInLink] / [completeEmailLinkSignIn].
 * With a custom domain later, add setLinkDomain(thatDomain) and update the intent filter.
 */
class FirebaseAuthService internal constructor(
    context: Context,
    private val session: FirebaseSession,
    private val db: FirebaseDatabase,
    /** Erases the account's database data and profile picture (FirebaseAccountEraser.erase). */
    private val eraseAccountData: suspend (uid: String) -> Unit,
    /** Deletion failed or was cancelled after [eraseAccountData] started (FirebaseAccountEraser.abandon). */
    private val deletionAbandoned: (uid: String) -> Unit,
    /** Runs while still signed in, so presence can mark us offline before the token goes away. */
    private val beforeSignOut: () -> Unit = {},
) : AuthService {

    private val auth: FirebaseAuth = session.auth
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        // Fires once on registration with the restored user, then on every sign-in/out.
        auth.addAuthStateListener { _state.value = stateOf(it.currentUser) }
    }

    override val currentUid: String? get() = session.currentUid

    override val pendingEmail: String? get() = prefs.getString(KEY_PENDING_EMAIL, null)

    override suspend fun signInWithGoogleIdToken(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = auth.signInWithCredential(credential).await().user
            ?: error("Google sign-in returned no user")
        _state.value = stateOf(user)
        writePrivateProfile(user, PROVIDER_GOOGLE)
    }

    override suspend fun sendEmailSignInLink(email: String) {
        val address = email.trim()
        require(Patterns.EMAIL_ADDRESS.matcher(address).matches()) { "That doesn't look like an e-mail address" }
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(CONTINUE_URL)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(ANDROID_PACKAGE, /* installIfNotAvailable = */ false, /* minimumVersion = */ null)
            .build()
        auth.sendSignInLinkToEmail(address, settings).await()
        prefs.edit().putString(KEY_PENDING_EMAIL, address).apply()
    }

    override fun isEmailSignInLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    override suspend fun completeEmailLinkSignIn(link: String, email: String?) {
        val address = email?.trim()?.takeIf { it.isNotEmpty() } ?: pendingEmail ?: throw EmailRequiredException()
        val user = auth.signInWithEmailLink(address, link).await().user
            ?: error("E-mail link sign-in returned no user")
        prefs.edit().remove(KEY_PENDING_EMAIL).apply()
        _state.value = stateOf(user)
        writePrivateProfile(user, PROVIDER_EMAIL_LINK)
    }

    override suspend fun signOut() {
        runCatching(beforeSignOut).onFailure { Log.w(TAG, "beforeSignOut failed", it) }
        auth.signOut()
        _state.value = AuthState.SignedOut
    }

    /**
     * Order: check the sign-in is recent enough → erase data while still authenticated (database
     * rules and the push relay need our token) → delete the Firebase Auth user, which also signs
     * out. The recency check runs first so a stale sign-in normally fails before anything is
     * deleted. If Firebase still refuses the last step, the data is already gone; the user signs
     * in again and retries, and the erase (idempotent) finds nothing or little left.
     */
    override suspend fun deleteAccount() {
        val user = auth.currentUser?.takeUnless { it.isAnonymous } ?: throw NotSignedInException()
        requireRecentSignIn(user)
        try {
            eraseAccountData(user.uid)
            user.delete().await()
        } catch (e: Throwable) {
            deletionAbandoned(user.uid)
            if (e is FirebaseAuthRecentLoginRequiredException) throw RecentLoginRequiredException()
            throw e
        }
        prefs.edit().remove(KEY_PENDING_EMAIL).apply()
        auth.signOut()
        _state.value = AuthState.SignedOut
    }

    /**
     * Firebase only deletes an account that signed in within the last few minutes (5 at the time
     * of writing). Both timestamps come from a freshly minted ID token, so both are Firebase
     * server time and no device clock is involved: iat = when this token was issued (server
     * "now"), auth_time = when the user last actually signed in. A margin below Firebase's limit
     * leaves time for the erase to finish before the final delete.
     */
    private suspend fun requireRecentSignIn(user: FirebaseUser) {
        val token = user.getIdToken(/* forceRefresh = */ true).await()
        val authTime = token.authTimestamp
        if (authTime <= 0L || token.issuedAtTimestamp - authTime > RECENT_SIGN_IN_SECONDS) {
            throw RecentLoginRequiredException()
        }
    }

    /**
     * users_private/{uid} {email, provider, createdAt}: owner-only. createdAt is server time and
     * only written the first time. Sign-in still succeeds if this fails (e.g. rules not published).
     */
    private suspend fun writePrivateProfile(user: FirebaseUser, provider: String) {
        try {
            val ref = db.getReference("users_private").child(user.uid)
            val exists = ref.child("createdAt").get().await().exists()
            val update = buildMap<String, Any?> {
                put("email", user.email?.take(MAX_EMAIL_LENGTH))
                put("provider", provider)
                if (!exists) put("createdAt", ServerValue.TIMESTAMP)
            }
            ref.updateChildren(update).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write users_private", e)
        }
    }

    private fun stateOf(user: FirebaseUser?): AuthState {
        // Leftover v1 anonymous users count as signed out (see FirebaseSession).
        if (user == null || user.isAnonymous) return AuthState.SignedOut
        return AuthState.SignedIn(
            uid = user.uid,
            email = user.email,
            displayName = user.displayName ?: user.providerData.firstNotNullOfOrNull { it.displayName },
            photoUrl = (user.photoUrl ?: user.providerData.firstNotNullOfOrNull { it.photoUrl })?.toString(),
            provider = providerOf(user.providerData.map { it.providerId }),
        )
    }

    companion object {
        private const val TAG = "SyncItAuth"
        private const val PREFS = "vyro_auth"
        private const val KEY_PENDING_EMAIL = "pendingEmail"
        private const val MAX_EMAIL_LENGTH = 254

        /** Our limit for "recent" sign-in before deleting (Firebase's own is 5 minutes). */
        private const val RECENT_SIGN_IN_SECONDS = 4 * 60L

        const val ANDROID_PACKAGE = "com.syncit.app"

        /** Default Firebase handler domain the e-mail link points at (App Link host for :app). */
        const val EMAIL_LINK_DOMAIN = "syncit-watchparty.firebaseapp.com"

        /** Firebase's reserved auth paths (/__/auth/action, /__/auth/links): App Link path for :app. */
        const val EMAIL_LINK_PATH_PREFIX = "/__/auth/"

        /** Continue URL; opened in the browser if the app can't handle the link. */
        const val CONTINUE_URL = "https://syncit-watchparty.web.app/"

        // Values stored in users_private/{uid}/provider.
        private const val PROVIDER_GOOGLE = "google"
        private const val PROVIDER_EMAIL_LINK = "emailLink"

        /**
         * Firebase provider ids → [SignInProvider]. An e-mail-link account shows up as
         * "password" (Firebase's e-mail provider; this app never uses passwords). Google wins
         * if an account has both linked.
         */
        internal fun providerOf(providerIds: List<String>): SignInProvider = when {
            "google.com" in providerIds -> SignInProvider.GOOGLE
            "password" in providerIds || "emailLink" in providerIds -> SignInProvider.EMAIL_LINK
            else -> SignInProvider.OTHER
        }
    }
}
