package com.syncit.app.ui.settings

import com.syncit.app.auth.GoogleSignIn
import com.syncit.app.ui.settings.DeleteAccountViewModel.Step
import com.syncit.core.auth.AuthService
import com.syncit.core.auth.RecentLoginRequiredException
import com.syncit.core.model.AuthState
import com.syncit.core.model.SignInProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs on Dispatchers.Unconfined: every step happens synchronously inside the call. */
class DeleteAccountViewModelTest {
    private val google = AuthState.SignedIn("u1", "alex@example.com", "Alex", null, SignInProvider.GOOGLE)
    private val emailUser = google.copy(provider = SignInProvider.EMAIL_LINK)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun vm(auth: FakeAuth, hooks: RecordingHooks = RecordingHooks()) =
        DeleteAccountViewModel(auth, flowOf("alex"), hooks, scope)

    @Test fun confirmationAcceptsUsernameOrDelete() {
        assertTrue(DeleteAccountViewModel.matchesConfirmation("alex", "alex"))
        assertTrue(DeleteAccountViewModel.matchesConfirmation(" @Alex ", "alex"))
        assertTrue(DeleteAccountViewModel.matchesConfirmation("DELETE", "alex"))
        assertTrue(DeleteAccountViewModel.matchesConfirmation("delete", null))
        assertFalse(DeleteAccountViewModel.matchesConfirmation("", "alex"))
        assertFalse(DeleteAccountViewModel.matchesConfirmation("@", "alex"))
        assertFalse(DeleteAccountViewModel.matchesConfirmation("alexx", "alex"))
        assertFalse(DeleteAccountViewModel.matchesConfirmation("alex", null))
    }

    @Test fun deletesAfterConfirmationWithProgress() {
        val auth = FakeAuth(google)
        val hooks = RecordingHooks()
        val gate = CompletableDeferred<Unit>()
        auth.onDelete = { gate.await() }
        val vm = vm(auth, hooks)

        vm.delete() // closed: nothing happens
        assertEquals(0, auth.deleteCalls)

        vm.open()
        assertEquals(Step.Confirm, vm.ui.value.step)
        assertEquals("alex", vm.ui.value.username)
        assertEquals(SignInProvider.GOOGLE, vm.ui.value.provider)

        vm.onTypedChange("alx")
        vm.delete() // not confirmed
        assertEquals(Step.Confirm, vm.ui.value.step)
        assertEquals(0, auth.deleteCalls)

        vm.onTypedChange("alex")
        vm.delete()
        assertEquals(Step.Deleting, vm.ui.value.step)
        assertFalse(vm.ui.value.dismissable)
        vm.close() // can't cancel mid-delete
        assertEquals(Step.Deleting, vm.ui.value.step)
        assertEquals(listOf("before"), hooks.events)

        gate.complete(Unit)
        assertEquals(Step.Deleted, vm.ui.value.step)
        assertEquals(listOf("before", "after:u1"), hooks.events)
        assertEquals(AuthState.SignedOut, auth.state.value)

        // The next sign-in clears the "account deleted" note.
        auth.mutableState.value = google.copy(uid = "u2")
        assertEquals(Step.Closed, vm.ui.value.step)
    }

    @Test fun recentLoginWithGoogleThenRetry() {
        val auth = FakeAuth(google)
        val hooks = RecordingHooks()
        auth.onDelete = { throw RecentLoginRequiredException() }
        val vm = vm(auth, hooks)
        vm.open()
        vm.onTypedChange("DELETE")
        vm.delete()
        assertEquals(Step.NeedsSignIn, vm.ui.value.step)
        assertEquals(listOf("before"), hooks.events) // still out of the room, not abandoned

        vm.onGoogleResult(GoogleSignIn.Result.Cancelled)
        assertEquals(Step.NeedsSignIn, vm.ui.value.step)

        // Another Google account would switch accounts: refused before signing in.
        vm.onGoogleResult(GoogleSignIn.Result.Token("t", email = "someone@example.com"))
        assertEquals(Step.NeedsSignIn, vm.ui.value.step)
        assertNotNull(vm.ui.value.error)
        assertEquals(0, auth.googleSignIns)

        vm.onGoogleStarted()
        assertEquals(Step.SigningIn, vm.ui.value.step)
        vm.onGoogleResult(GoogleSignIn.Result.Token("t", email = "Alex@example.com"))
        assertEquals(1, auth.googleSignIns)
        assertEquals(Step.Confirm, vm.ui.value.step)
        assertTrue(vm.ui.value.signedInAgain)
        assertNull(vm.ui.value.error)
        assertTrue(vm.ui.value.confirmed) // typed text kept

        auth.onDelete = {}
        vm.delete()
        assertEquals(Step.Deleted, vm.ui.value.step)
        assertEquals(listOf("before", "after:u1"), hooks.events) // prepared once only
        assertEquals(2, auth.deleteCalls)
    }

    @Test fun recentLoginWithEmailLink() {
        val auth = FakeAuth(emailUser)
        auth.onDelete = { throw RecentLoginRequiredException() }
        val vm = vm(auth)
        assertFalse(vm.onEmailLink("https://link")) // nobody waiting

        vm.open()
        vm.onTypedChange("alex")
        vm.delete()
        assertEquals(Step.NeedsSignIn, vm.ui.value.step)

        vm.sendSignInLink()
        assertEquals(Step.CheckInbox("alex@example.com"), vm.ui.value.step)
        assertEquals(listOf("alex@example.com"), auth.sentLinks)

        assertTrue(vm.onEmailLink("bad"))
        assertEquals(Step.CheckInbox("alex@example.com"), vm.ui.value.step)
        assertNotNull(vm.ui.value.error)

        assertTrue(vm.onEmailLink("good"))
        assertEquals(Step.Confirm, vm.ui.value.step)
        assertTrue(vm.ui.value.signedInAgain)
        assertEquals(listOf("good" to "alex@example.com"), auth.completedLinks)
    }

    @Test fun differentAccountAfterSignInBlocksDeletion() {
        val auth = FakeAuth(google)
        auth.onDelete = { throw RecentLoginRequiredException() }
        auth.googleSignsInAs = google.copy(uid = "other")
        val vm = vm(auth)
        vm.open()
        vm.onTypedChange("DELETE")
        vm.delete()
        // Google didn't report the email, so the uid check after sign-in has to catch it.
        vm.onGoogleResult(GoogleSignIn.Result.Token("t", email = null))
        assertEquals(Step.NeedsSignIn, vm.ui.value.step)
        assertNotNull(vm.ui.value.error)
        assertEquals(1, auth.deleteCalls)
    }

    @Test fun cancellingOrFailingResumesPresence() {
        val auth = FakeAuth(google)
        val hooks = RecordingHooks()
        auth.onDelete = { throw RecentLoginRequiredException() }
        val vm = vm(auth, hooks)
        vm.open()
        vm.close() // nothing prepared yet: nothing to undo
        assertEquals(Step.Closed, vm.ui.value.step)
        assertEquals(emptyList<String>(), hooks.events)

        vm.open()
        vm.onTypedChange("DELETE")
        vm.delete()
        vm.close()
        assertEquals(Step.Closed, vm.ui.value.step)
        assertEquals(listOf("before", "abandon"), hooks.events)

        hooks.events.clear()
        auth.onDelete = { error("offline") }
        vm.open()
        assertEquals("", vm.ui.value.typed) // a fresh start
        vm.onTypedChange("DELETE")
        vm.delete()
        assertEquals(Step.Confirm, vm.ui.value.step)
        assertTrue(vm.ui.value.error!!.contains("offline"))
        assertEquals(listOf("before", "abandon"), hooks.events)
    }

    @Test fun signedOutElsewhereClosesTheSheet() {
        val auth = FakeAuth(google)
        val hooks = RecordingHooks()
        auth.onDelete = { throw RecentLoginRequiredException() }
        val vm = vm(auth, hooks)
        vm.open()
        vm.onTypedChange("DELETE")
        vm.delete()
        auth.mutableState.value = AuthState.SignedOut
        assertEquals(Step.Closed, vm.ui.value.step)
        assertEquals(listOf("before", "abandon"), hooks.events)
    }

    private class RecordingHooks : AccountDeletionHooks {
        val events = mutableListOf<String>()
        override suspend fun beforeDelete() { events += "before" }
        override suspend fun afterDelete(uid: String) { events += "after:$uid" }
        override suspend fun abandon() { events += "abandon" }
    }

    private class FakeAuth(me: AuthState.SignedIn) : AuthService {
        val mutableState = MutableStateFlow<AuthState>(me)
        override val state: StateFlow<AuthState> = mutableState
        override val currentUid: String? get() = (mutableState.value as? AuthState.SignedIn)?.uid
        override val pendingEmail: String? = null

        var onDelete: suspend () -> Unit = {}
        var deleteCalls = 0
        var googleSignsInAs: AuthState.SignedIn = me
        var googleSignIns = 0
        val sentLinks = mutableListOf<String>()
        val completedLinks = mutableListOf<Pair<String, String?>>()

        override suspend fun signInWithGoogleIdToken(idToken: String) {
            googleSignIns++
            mutableState.value = googleSignsInAs
        }

        override suspend fun sendEmailSignInLink(email: String) { sentLinks += email }

        override fun isEmailSignInLink(link: String): Boolean = true

        override suspend fun completeEmailLinkSignIn(link: String, email: String?) {
            if (link == "bad") error("expired")
            completedLinks += link to email
        }

        override suspend fun signOut() { mutableState.value = AuthState.SignedOut }

        override suspend fun deleteAccount() {
            deleteCalls++
            onDelete()
            mutableState.value = AuthState.SignedOut
        }
    }
}
