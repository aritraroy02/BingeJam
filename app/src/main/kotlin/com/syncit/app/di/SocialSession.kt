package com.syncit.app.di

import com.syncit.app.data.UserPrefs
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.AuthState
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Runs presence and the activity tracker for as long as someone is signed in (process lifetime,
 * not tied to a screen). Invisible = presence stopped and an empty share set.
 */
class SocialSession(
    private val social: SocialServices,
    prefs: UserPrefs,
    sessionController: SyncSessionController,
    scope: CoroutineScope,
) {
    /** What the tracker may publish: the user's picks, or nothing while invisible. */
    private val effectiveSharedApps: StateFlow<Set<ActivityApp>> =
        combine(prefs.sharedApps, prefs.invisible) { apps, invisible -> if (invisible) emptySet() else apps }
            .stateIn(scope, SharingStarted.Eagerly, if (prefs.invisible.value) emptySet() else prefs.sharedApps.value)

    /**
     * The room code to attach to shared movie activity ("Ask to join" lands there), only while
     * we're actually on the room's title. CORRECTING/CATCHING_UP are still on the title; counting
     * them too stops the code flapping in presence during a drift correction.
     */
    private val currentRoomCode: StateFlow<String?> = sessionController.state
        .map { s -> s.roomCode.takeIf { s.status in ON_TITLE } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            val signedInUid = social.auth.state.map { (it as? AuthState.SignedIn)?.uid }.distinctUntilChanged()
            combine(signedInUid, prefs.invisible, ::Pair).collect { (uid, invisible) ->
                // Restart on every change so a new account never inherits the previous one's session.
                social.activityTracker.stop()
                social.presence.stop()
                if (uid != null) {
                    if (!invisible) social.presence.start()
                    social.activityTracker.start(effectiveSharedApps, currentRoomCode)
                }
            }
        }
    }

    private companion object {
        val ON_TITLE = setOf(SyncStatus.SYNCING, SyncStatus.CORRECTING, SyncStatus.CATCHING_UP)
    }
}
