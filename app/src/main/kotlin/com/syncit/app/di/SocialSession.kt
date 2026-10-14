package com.syncit.app.di

import com.syncit.app.data.UserPrefs
import com.syncit.core.model.AuthState
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    /**
     * True while we're actually on the room's title. CORRECTING/CATCHING_UP are still on the
     * title; counting them stops the flag flapping in presence during a drift correction.
     */
    private val inWatchParty: StateFlow<Boolean> = sessionController.state
        .map { it.roomCode != null && it.status in ON_TITLE }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
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
                        combine(prefs.activitySetupDone, prefs.invisible) { setupDone, invisible ->
                            uid.takeIf { it in setupDone && !invisible }
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

    private companion object {
        val ON_TITLE = setOf(SyncStatus.SYNCING, SyncStatus.CORRECTING, SyncStatus.CATCHING_UP)
    }
}
