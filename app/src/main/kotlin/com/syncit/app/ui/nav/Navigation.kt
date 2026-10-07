package com.syncit.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.diagnostics.DiagnosticsScreen
import com.syncit.app.ui.diagnostics.DiagnosticsViewModel
import com.syncit.app.ui.home.HomeScreen
import com.syncit.app.ui.home.HomeViewModel
import com.syncit.app.ui.join.JoinPreviewScreen
import com.syncit.app.ui.join.JoinPreviewViewModel
import com.syncit.app.ui.onboarding.OnboardingScreen
import com.syncit.app.ui.room.RoomScreen
import com.syncit.app.ui.room.RoomViewModel
import com.syncit.app.ui.settings.SettingsScreen

sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
    data class JoinPreview(val code: String) : Screen
    data class Room(val code: String) : Screen
}

/** A tiny back stack. A handful of screens don't justify a navigation library. */
class NavViewModel(start: Screen) : ViewModel() {
    val backStack = mutableStateListOf(start)

    val current: Screen get() = backStack.last()

    fun push(screen: Screen) {
        backStack.add(screen)
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
 * [pendingJoinCode] is set by MainActivity from an invite link. It's parked in prefs until
 * onboarding is done, then opens the join preview.
 */
@Composable
fun SyncItApp(container: AppContainer, pendingJoinCode: MutableState<String?>) {
    val prefs = container.prefs
    val nav: NavViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val pending = prefs.pendingJoinCode
                when {
                    !prefs.onboardingDone -> NavViewModel(Screen.Onboarding)
                    pending != null -> NavViewModel(Screen.Home).also { it.push(Screen.JoinPreview(pending)) }
                    else -> NavViewModel(Screen.Home)
                }
            }
        },
    )

    BackHandler(enabled = nav.backStack.size > 1) { nav.pop() }

    LaunchedEffect(pendingJoinCode.value) {
        val code = pendingJoinCode.value ?: return@LaunchedEffect
        pendingJoinCode.value = null
        prefs.pendingJoinCode = code
        if (prefs.onboardingDone && (nav.current as? Screen.JoinPreview)?.code != code) {
            nav.reset(Screen.Home, Screen.JoinPreview(code))
        }
    }

    when (val screen = nav.current) {
        Screen.Onboarding -> OnboardingScreen(
            container = container,
            onDone = {
                prefs.onboardingDone = true
                val pending = prefs.pendingJoinCode
                when {
                    pending != null -> nav.reset(Screen.Home, Screen.JoinPreview(pending))
                    nav.backStack.size > 1 -> nav.pop() // opened from Settings
                    else -> nav.reset(Screen.Home)
                }
            },
        )

        Screen.Home -> {
            val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
            HomeScreen(
                viewModel = vm,
                onEnterRoom = { code -> nav.push(Screen.Room(code)) },
                onJoinCode = { code -> nav.push(Screen.JoinPreview(code)) },
                onOpenSettings = { nav.push(Screen.Settings) },
            )
        }

        Screen.Settings -> SettingsScreen(
            prefs = prefs,
            onBack = nav::pop,
            onOpenSetup = { nav.push(Screen.Onboarding) },
            onOpenDiagnostics = { nav.push(Screen.Diagnostics) },
        )

        Screen.Diagnostics -> {
            val vm: DiagnosticsViewModel =
                viewModel(factory = viewModelFactory { initializer { DiagnosticsViewModel(container.gateway) } })
            DiagnosticsScreen(viewModel = vm, onBack = nav::pop)
        }

        is Screen.JoinPreview -> {
            val vm: JoinPreviewViewModel = viewModel(
                key = "join-${screen.code}",
                factory = viewModelFactory { initializer { JoinPreviewViewModel(screen.code, container) } },
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
}
