package com.syncit.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.syncit.app.di.AppContainer
import com.syncit.app.push.PushNotifications
import com.syncit.app.ui.account.AccountFlow
import com.syncit.app.ui.account.AuthViewModel
import com.syncit.app.ui.account.UsernameScreen
import com.syncit.app.ui.account.UsernameViewModel
import com.syncit.app.ui.activity.ActivitySetupScreen
import com.syncit.app.ui.chat.DmScreen
import com.syncit.app.ui.chat.DmViewModel
import com.syncit.app.ui.common.BrandWordmark
import com.syncit.app.ui.diagnostics.DiagnosticsScreen
import com.syncit.app.ui.diagnostics.DiagnosticsViewModel
import com.syncit.app.ui.friends.FriendsScreen
import com.syncit.app.ui.friends.FriendsViewModel
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.home.HomeScreen
import com.syncit.app.ui.home.HomeViewModel
import com.syncit.app.ui.join.JoinPreviewScreen
import com.syncit.app.ui.join.JoinPreviewViewModel
import com.syncit.app.ui.onboarding.OnboardingScreen
import com.syncit.app.ui.room.RoomScreen
import com.syncit.app.ui.room.RoomViewModel
import com.syncit.app.ui.settings.DeleteAccountViewModel
import com.syncit.app.ui.settings.EditProfileScreen
import com.syncit.app.ui.settings.EditProfileViewModel
import com.syncit.app.ui.settings.SettingsScreen
import com.syncit.app.ui.settings.SettingsViewModel
import com.syncit.app.ui.social.SocialBanners
import com.syncit.app.ui.social.SocialEventsViewModel
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand

/** Screens once signed in and set up. The account flow before that is driven by [Gate]. */
sealed interface Screen {
    data object Home : Screen
    data object Friends : Screen
    data class Chat(val friendUid: String) : Screen
    data object Settings : Screen
    data object EditProfile : Screen

    /** Permissions walkthrough reopened from Settings. */
    data object Permissions : Screen
    data object Diagnostics : Screen
    data class JoinPreview(val code: String, val autoJoin: Boolean = false) : Screen
    data class Room(val code: String) : Screen
}

/** A tiny back stack. A handful of screens don't justify a navigation library. */
class NavViewModel : ViewModel() {
    val backStack = mutableStateListOf<Screen>(Screen.Home)

    val current: Screen get() = backStack.last()

    fun push(screen: Screen) {
        if (current != screen) backStack.add(screen)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun reset(vararg screens: Screen) {
        backStack.clear()
        backStack.addAll(screens)
    }
}

/**
 * [pendingJoinCode] (room invite links) and [pendingEmailLink] (email sign-in links) are set by
 * MainActivity. A room code is parked in prefs until the user is signed in and set up, then opens
 * the join preview.
 */
@Composable
fun SyncItApp(
    container: AppContainer,
    pendingJoinCode: MutableState<String?>,
    pendingEmailLink: MutableState<String?>,
    pendingNotification: MutableState<PushNotifications.Target?>,
) {
    val prefs = container.prefs
    val app: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(container) } })
    val nav: NavViewModel = viewModel(factory = viewModelFactory { initializer { NavViewModel() } })
    val gate by app.gate.collectAsStateWithLifecycle()

    LaunchedEffect(pendingJoinCode.value, gate) {
        val code = pendingJoinCode.value ?: return@LaunchedEffect
        pendingJoinCode.value = null
        prefs.pendingJoinCode = code
        if (gate == Gate.Ready && (nav.current as? Screen.JoinPreview)?.code != code) {
            nav.reset(Screen.Home, Screen.JoinPreview(code))
        }
    }

    // A tapped notification opens the chat or Friends once the app is ready (it waits through
    // sign-in and setup if needed).
    LaunchedEffect(pendingNotification.value, gate) {
        val target = pendingNotification.value ?: return@LaunchedEffect
        if (gate != Gate.Ready) return@LaunchedEffect
        pendingNotification.value = null
        when (target.type) {
            PushNotifications.TYPE_DM -> nav.reset(Screen.Home, Screen.Chat(target.uid))
            else -> nav.reset(Screen.Home, Screen.Friends)
        }
    }

    // Becoming ready (sign-in + setup finished) opens an invite that was waiting; signing out
    // forgets where we were.
    LaunchedEffect(gate) {
        if (gate == Gate.Ready) {
            prefs.pendingJoinCode?.let { code ->
                if ((nav.current as? Screen.JoinPreview)?.code != code) nav.reset(Screen.Home, Screen.JoinPreview(code))
            }
        } else if (gate == Gate.SignedOut) {
            nav.reset(Screen.Home)
        }
    }

    when (val g = gate) {
        Gate.Loading -> Splash()

        Gate.ProfileError -> Splash(
            error = "Couldn't load your profile. Check your connection and try again.",
            onRetry = app::retryProfile,
            onSignOut = app::signOut,
        )

        Gate.SignedOut -> {
            val auth: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(container) } })
            LaunchedEffect(pendingEmailLink.value) {
                val link = pendingEmailLink.value ?: return@LaunchedEffect
                pendingEmailLink.value = null
                auth.onEmailLink(link)
            }
            val deletion = deleteAccountViewModel(container)
            val deletionUi by deletion.ui.collectAsStateWithLifecycle()
            AccountFlow(
                auth,
                useFakeGoogle = container.social.isFake,
                accountDeleted = deletionUi.step == DeleteAccountViewModel.Step.Deleted,
                onDismissDeleted = deletion::close,
            )
        }

        is Gate.NeedsUsername -> {
            val vm: UsernameViewModel = viewModel(
                key = "username-${g.seedName}",
                factory = viewModelFactory { initializer { UsernameViewModel(container, g.seedName) } },
            )
            UsernameScreen(vm)
        }

        is Gate.NeedsActivitySetup -> ActivitySetupScreen(prefs, onDone = { prefs.markActivitySetupDone(g.uid) })

        Gate.NeedsPermissions -> OnboardingScreen(container = container, onDone = { prefs.onboardingDone = true })

        Gate.Ready -> {
            val deletion = deleteAccountViewModel(container)
            LaunchedEffect(pendingEmailLink.value) {
                val link = pendingEmailLink.value ?: return@LaunchedEffect
                pendingEmailLink.value = null
                // Already signed in, so a link only matters when it's the fresh sign-in that
                // deleting the account asked for; then make sure its sheet is on screen.
                if (deletion.onEmailLink(link) && nav.current != Screen.Settings) nav.reset(Screen.Home, Screen.Settings)
            }
            MainNav(container, nav, deletion)
        }
    }
}

@Composable
private fun MainNav(container: AppContainer, nav: NavViewModel, deletion: DeleteAccountViewModel) {
    val events: SocialEventsViewModel = viewModel(factory = viewModelFactory { initializer { SocialEventsViewModel(container) } })

    LaunchedEffect(events) {
        events.navEvents.collect { event ->
            when (event) {
                is SocialEventsViewModel.NavEvent.OpenRoom -> nav.reset(Screen.Home, Screen.Room(event.code))
                is SocialEventsViewModel.NavEvent.JoinRoom -> nav.reset(Screen.Home, Screen.JoinPreview(event.code, autoJoin = true))
            }
        }
    }

    BackHandler(enabled = nav.backStack.size > 1) { nav.pop() }

    Box(Modifier.fillMaxSize()) {
        when (val screen = nav.current) {
            Screen.Home -> {
                val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
                HomeScreen(
                    viewModel = vm,
                    onEnterRoom = { code -> nav.push(Screen.Room(code)) },
                    onJoinCode = { code -> nav.push(Screen.JoinPreview(code)) },
                    onOpenSettings = { nav.push(Screen.Settings) },
                    onOpenFriends = { nav.push(Screen.Friends) },
                    onOpenChat = { uid -> nav.push(Screen.Chat(uid)) },
                )
            }

            Screen.Friends -> {
                val vm: FriendsViewModel = viewModel(factory = viewModelFactory { initializer { FriendsViewModel(container) } })
                FriendsScreen(vm, onBack = nav::pop)
            }

            is Screen.Chat -> {
                val vm: DmViewModel = viewModel(
                    key = "dm-${screen.friendUid}",
                    factory = viewModelFactory { initializer { DmViewModel(screen.friendUid, container) } },
                )
                DmScreen(vm, onBack = nav::pop)
            }

            Screen.Settings -> {
                val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container) } })
                SettingsScreen(
                    viewModel = vm,
                    deleteAccount = deletion,
                    useFakeGoogle = container.social.isFake,
                    onBack = nav::pop,
                    onEditProfile = { nav.push(Screen.EditProfile) },
                    onOpenSetup = { nav.push(Screen.Permissions) },
                    onOpenDiagnostics = { nav.push(Screen.Diagnostics) },
                )
            }

            Screen.EditProfile -> {
                // New instance each time so it starts from the saved profile.
                val vm: EditProfileViewModel = viewModel(
                    key = "edit-profile-${nav.backStack.size}",
                    factory = viewModelFactory { initializer { EditProfileViewModel(container) } },
                )
                EditProfileScreen(vm, onBack = nav::pop)
            }

            Screen.Permissions -> OnboardingScreen(container = container, onDone = nav::pop)

            Screen.Diagnostics -> {
                val vm: DiagnosticsViewModel =
                    viewModel(factory = viewModelFactory { initializer { DiagnosticsViewModel(container.gateway) } })
                DiagnosticsScreen(viewModel = vm, onBack = nav::pop)
            }

            is Screen.JoinPreview -> {
                val vm: JoinPreviewViewModel = viewModel(
                    key = "join-${screen.code}-${screen.autoJoin}",
                    factory = viewModelFactory { initializer { JoinPreviewViewModel(screen.code, container, screen.autoJoin) } },
                )
                JoinPreviewScreen(
                    viewModel = vm,
                    onJoined = { code -> nav.reset(Screen.Home, Screen.Room(code)) },
                    onBack = nav::pop,
                )
            }

            is Screen.Room -> {
                val vm: RoomViewModel = viewModel(
                    key = "room-${screen.code}",
                    factory = viewModelFactory { initializer { RoomViewModel(screen.code, container) } },
                )
                RoomScreen(
                    viewModel = vm,
                    onBack = nav::pop,
                    onLeft = { nav.reset(Screen.Home) },
                )
            }
        }

        SocialBanners(events)
    }
}

/** Activity-scoped, like every ViewModel here: Settings, the email-link handoff and the welcome note share it. */
@Composable
private fun deleteAccountViewModel(container: AppContainer): DeleteAccountViewModel {
    val appContext = LocalContext.current.applicationContext
    return viewModel(factory = viewModelFactory { initializer { DeleteAccountViewModel.create(container, appContext) } })
}

/** Brand + spinner while auth/profile load; with [error], a retry button instead. */
@Composable
private fun Splash(error: String? = null, onRetry: (() -> Unit)? = null, onSignOut: (() -> Unit)? = null) {
    val p = Glass.palette
    GlassScreen {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandWordmark()
            Text(Brand.TAGLINE, style = MaterialTheme.typography.bodyLarge, color = p.textSecondary)
            if (error == null) {
                CircularProgressIndicator(color = p.accent)
            } else {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = p.danger)
                if (onRetry != null) GlassButton("Try again", onClick = onRetry)
                if (onSignOut != null) GlassButton("Sign out", onClick = onSignOut, style = GlassButtonStyle.Secondary)
            }
        }
    }
}
