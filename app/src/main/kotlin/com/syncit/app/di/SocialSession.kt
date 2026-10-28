package com.syncit.app.di

import com.syncit.app.data.UserPrefs
import com.syncit.app.push.PushNotifications
import com.syncit.core.model.AuthState
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Runs presence and the activity tracker while an account is signed in, has finished the
 * activity-sharing setup page on this phone, and isn't invisible (process lifetime, not tied to a
 * screen). Also points [UserPrefs]' per-account sharing settings at the signed-in account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocialSession(
    private val social: SocialServices,
    private val prefs: UserPrefs,
    sessionController: SyncSessionController,
    scope: CoroutineScope,
) {
    /** Set while an account deletion runs, so nothing writes presence/activity for a dying account. */
    private val paused = MutableStateFlow(false)

    /**
     * True while we're actually on the room's title. CORRECTING/CATCHING_UP are still on the
     * title; counting them stops the flag flapping in presence during a drift correction.
     */
    private val inWatchParty: StateFlow<Boolean> = sessionController.state
        .map { it.roomCode != null && it.status in ON_TITLE }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Push token: registered for every signed-in account, even invisible ones or before the
        // activity setup (messages and friend requests aren't activity).
        scope.launch {
            social.auth.state
                .map { (it as? AuthState.SignedIn)?.uid }
                .distinctUntilChanged()
                .collect { uid -> if (uid != null) PushNotifications.registerToken(social.push) }
        }
        scope.launch {
            social.auth.state
                .map { (it as? AuthState.SignedIn)?.uid }
                .distinctUntilChanged()
                // Synchronously, before anything reads sharedApps/invisible for the new account.
                .onEach { prefs.bindAccount(it) }
                .flatMapLatest { uid ->
                    if (uid == null) {
                        flowOf(null)
                    } else {
                        combine(prefs.activitySetupDone, prefs.invisible, paused) { setupDone, invisible, isPaused ->
                            uid.takeIf { it in setupDone && !invisible && !isPaused }
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { activeUid ->
                    social.activityTracker.stop()
                    social.presence.stop()
                    if (activeUid != null) {
                        social.presence.start()
                        social.activityTracker.start(prefs.sharedApps, inWatchParty)
                    }
                }
        }
    }

    /**
     * Before deleting the account: stop presence and the activity tracker now (not on the next
     * collector pass), so their last writes happen while the account still exists. The push
     * token stays registered; the eraser removes it from the account.
     */
    fun pauseForAccountDeletion() {
        paused.value = true
        social.activityTracker.stop()
        social.presence.stop()
    }

    /**
     * Deletion finished or was abandoned. If the account still exists, presence and activity
     * start again; after a successful deletion we're signed out, so nothing restarts until the
     * next sign-in.
     */
    fun endAccountDeletion() {
        paused.value = false
    }

    private companion object {
        val ON_TITLE = setOf(SyncStatus.SYNCING, SyncStatus.CORRECTING, SyncStatus.CATCHING_UP)
    }
}
