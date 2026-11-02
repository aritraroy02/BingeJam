package com.syncit.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import com.syncit.app.ui.glass.EmphasizedEase
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.rememberReduceMotion
import com.syncit.app.ui.home.HomeScreen
import com.syncit.app.ui.home.HomeViewModel
import com.syncit.app.ui.join.JoinPreviewScreen
import com.syncit.app.ui.join.JoinPreviewViewModel
import com.syncit.app.ui.onboarding.OnboardingScreen
import com.syncit.app.ui.profile.ProfileScreen
import com.syncit.app.ui.profile.ProfileViewModel
import com.syncit.app.ui.room.OngoingRoomBar
import com.syncit.app.ui.room.OngoingRoomViewModel
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.syncit.app.ui.chat.ChatsScreen
import com.syncit.app.ui.chat.ChatsViewModel
import com.syncit.app.ui.glass.LocalHazeState
import com.syncit.app.ui.glass.LocalOpenSheets
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Screens once signed in and set up. The account flow before that is driven by [Gate]. */
sealed interface Screen {
    data object Home : Screen

    /** Conversations, newest first (bottom bar). */
    data object Chats : Screen
    data object Friends : Screen
    data class Chat(val friendUid: String) : Screen
    data object Settings : Screen

    /** Your own profile (opened from your picture on Home). */
    data object Profile : Screen
    data object EditProfile : Screen

    /** Permissions walkthrough reopened from Settings. */
    data object Permissions : Screen
    data object Diagnostics : Screen
    data class JoinPreview(val code: String, val autoJoin: Boolean = false) : Screen
    /** [invite]: open on "Invite friends" (rooms started from a trending title). */
    data class Room(val code: String, val waitingFor: String? = null, val invite: Boolean = false) : Screen
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

    /** Back to [code]'s room: the existing entry (keeping its state) if it's in the stack, else a new one. */
    fun openRoom(code: String) {
        val existing = backStack.lastOrNull { it is Screen.Room && it.code == code } ?: Screen.Room(code)
        if (current == existing) return
        backStack.remove(existing)
        backStack.add(existing)
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
            PushNotifications.TYPE_DM -> nav.reset(Screen.Chats, Screen.Chat(target.uid))
            // Join requests and invites are answered from the banner shown over Home.
            PushNotifications.TYPE_JOIN_REQUEST, PushNotifications.TYPE_INVITE -> nav.reset(Screen.Home)
            else -> nav.reset(Screen.Friends)
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
                if (deletion.onEmailLink(link) && nav.current != Screen.Settings) nav.reset(Screen.Settings)
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
    // Back on another tab goes to Home first, like most apps with a bottom bar.
    BackHandler(enabled = nav.backStack.size == 1 && nav.current != Screen.Home) { nav.reset(Screen.Home) }

    val tabs: MainTabsViewModel = viewModel(factory = viewModelFactory { initializer { MainTabsViewModel(container) } })
    val unreadChats by tabs.unreadChats.collectAsStateWithLifecycle()
    val friendRequests by tabs.friendRequests.collectAsStateWithLifecycle()
    // The bar shows on the four tab screens themselves, not on screens opened from them.
    val currentTab = if (nav.backStack.size == 1) tabFor(nav.current) else null
    val tabHaze = rememberHazeState()
    // Sheets (movie, friend, message actions) hide the bar so it doesn't cover their buttons.
    val openSheets = remember { mutableIntStateOf(0) }

    val roomBar: OngoingRoomViewModel = viewModel(factory = viewModelFactory { initializer { OngoingRoomViewModel(container) } })
    val ongoing by roomBar.ongoing.collectAsStateWithLifecycle()
    val showRoomBar = ongoing?.let { o -> (nav.current as? Screen.Room)?.code != o.code } == true

    val reduceMotion = rememberReduceMotion()
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = showRoomBar, enter = expandVertically(), exit = shrinkVertically()) {
            ongoing?.let { o ->
                OngoingRoomBar(
                    ongoing = o,
                    onOpen = { nav.openRoom(o.code) },
                    onMute = roomBar::setMuted,
                    onLeave = roomBar::leave,
                )
            }
        }
        // Under the bar, screens skip their own status-bar padding (the bar already covers it).
        Box(Modifier.fillMaxSize().then(if (showRoomBar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            CompositionLocalProvider(
                LocalBottomBarInset provides if (currentTab != null) TabBarInset else 0.dp,
                LocalOpenSheets provides openSheets,
            ) {
                Box(Modifier.fillMaxSize().hazeSource(tabHaze)) {
                    // Deeper screens slide in from the right, going back slides them out; jumps elsewhere
                    // (notification, invite) crossfade. Depth travels with the screen so a screen that's
                    // leaving keeps the depth it was shown at.
                    AnimatedContent(
                        targetState = NavEntry(nav.current, nav.backStack.size),
                        transitionSpec = {
                            val forward = targetState.depth > initialState.depth
                            val back = targetState.depth < initialState.depth
                            when {
                                reduceMotion -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                forward -> (slideInHorizontally(tween(320, easing = EmphasizedEase)) { it / 4 } + fadeIn(tween(220))) togetherWith
                                    (slideOutHorizontally(tween(320, easing = EmphasizedEase)) { -it / 8 } + fadeOut(tween(180)))
                                back -> (slideInHorizontally(tween(300, easing = EmphasizedEase)) { -it / 8 } + fadeIn(tween(220))) togetherWith
                                    (slideOutHorizontally(tween(260, easing = EmphasizedEase)) { it / 4 } + fadeOut(tween(160)))
                                else -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                            }
                        },
                        contentKey = { it.screen },
                        label = "screens",
                    ) { entry ->
                        NavScreen(entry, container, nav, deletion)
                    }
                }
            }

            SocialBanners(events)

            BottomTabs(
                tab = currentTab?.takeIf { openSheets.intValue == 0 },
                unreadChats = unreadChats,
                friendRequests = friendRequests,
                haze = tabHaze,
                onSelect = { tab -> nav.reset(screenFor(tab)) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** A screen plus how deep in the back stack it sits, for picking the transition direction. */
private data class NavEntry(val screen: Screen, val depth: Int)

/** The floating tab bar, sliding in on the tab screens. Glass over whatever scrolls underneath. */
@Composable
private fun BottomTabs(
    tab: MainTab?,
    unreadChats: Int,
    friendRequests: Int,
    haze: HazeState,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = tab != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 2 },
        modifier = modifier,
    ) {
        CompositionLocalProvider(LocalHazeState provides haze) {
            MainTabBar(current = tab ?: MainTab.HOME, unreadChats = unreadChats, friendRequests = friendRequests, onSelect = onSelect)
        }
    }
}

private fun tabFor(screen: Screen): MainTab? = when (screen) {
    Screen.Home -> MainTab.HOME
    Screen.Chats -> MainTab.CHATS
    Screen.Friends -> MainTab.FRIENDS
    Screen.Settings -> MainTab.SETTINGS
    else -> null
}

private fun screenFor(tab: MainTab): Screen = when (tab) {
    MainTab.HOME -> Screen.Home
    MainTab.CHATS -> Screen.Chats
    MainTab.FRIENDS -> Screen.Friends
    MainTab.SETTINGS -> Screen.Settings
}

/** Badges on the bottom bar: unread messages (all chats) and friend requests. */
class MainTabsViewModel(container: AppContainer) : ViewModel() {
    val unreadChats: StateFlow<Int> = container.social.dms.observeUnreadCounts()
        .map { it.values.sum() }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val friendRequests: StateFlow<Int> = container.social.friends.observeIncomingRequests()
        .map { it.size }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
private fun NavScreen(entry: NavEntry, container: AppContainer, nav: NavViewModel, deletion: DeleteAccountViewModel) {
    run {
        when (val screen = entry.screen) {
            Screen.Home -> {
                val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
                HomeScreen(
                    viewModel = vm,
                    onEnterRoom = { code -> nav.push(Screen.Room(code)) },
                    onStartRoomWith = { code, friendUid -> nav.push(Screen.Room(code, waitingFor = friendUid)) },
                    onOpenProfile = { nav.push(Screen.Profile) },
                    onWatchWithFriends = { code -> nav.push(Screen.Room(code, invite = true)) },
                    onOpenChat = { uid -> nav.push(Screen.Chat(uid)) },
                )
            }

            Screen.Chats -> {
                val vm: ChatsViewModel = viewModel(factory = viewModelFactory { initializer { ChatsViewModel(container) } })
                ChatsScreen(vm, onOpenChat = { uid -> nav.push(Screen.Chat(uid)) })
            }

            Screen.Friends -> {
                val vm: FriendsViewModel = viewModel(factory = viewModelFactory { initializer { FriendsViewModel(container) } })
                FriendsScreen(vm, onBack = if (entry.depth > 1) nav::pop else null)
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
                    onBack = if (entry.depth > 1) nav::pop else null,
                    onEditProfile = { nav.push(Screen.EditProfile) },
                    onOpenSetup = { nav.push(Screen.Permissions) },
                    onOpenDiagnostics = { nav.push(Screen.Diagnostics) },
                )
            }

            Screen.Profile -> {
                val vm: ProfileViewModel = viewModel(factory = viewModelFactory { initializer { ProfileViewModel(container) } })
                ProfileScreen(
                    viewModel = vm,
                    onBack = nav::pop,
                    onEditProfile = { nav.push(Screen.EditProfile) },
                    onOpenSettings = { nav.push(Screen.Settings) },
                )
            }

            Screen.EditProfile -> {
                // New instance each time so it starts from the saved profile.
                val vm: EditProfileViewModel = viewModel(
                    key = "edit-profile-${entry.depth}",
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
                    key = "room-${screen.code}-${screen.waitingFor}",
                    factory = viewModelFactory { initializer { RoomViewModel(screen.code, container, screen.waitingFor) } },
                )
                RoomScreen(
                    viewModel = vm,
                    onBack = nav::pop,
                    onLeft = { nav.reset(Screen.Home) },
                    startWithInvite = screen.invite,
                )
            }
        }
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
