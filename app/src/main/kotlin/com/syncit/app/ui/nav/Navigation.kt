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
import com.syncit.app.ui.onboarding.OnboardingScreen
import com.syncit.app.ui.room.RoomScreen
import com.syncit.app.ui.room.RoomViewModel

sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data object Diagnostics : Screen
    data class Room(val code: String) : Screen
}

/** A tiny back stack. Five screens don't justify a navigation library. */
class NavViewModel(start: Screen) : ViewModel() {
    val backStack = mutableStateListOf(start)

    val current: Screen get() = backStack.last()

    fun push(screen: Screen) {
        backStack.add(screen)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun reset(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }
}

@Composable
fun SyncItApp(container: AppContainer, pendingJoinCode: MutableState<String?>) {
    val nav: NavViewModel = viewModel(
        factory = viewModelFactory {
            initializer { NavViewModel(if (container.prefs.onboardingDone) Screen.Home else Screen.Onboarding) }
        },
    )

    BackHandler(enabled = nav.backStack.size > 1) { nav.pop() }

    // A join link while onboarded jumps to Home, where the code is pre-filled.
    LaunchedEffect(pendingJoinCode.value) {
        if (pendingJoinCode.value != null && container.prefs.onboardingDone && nav.current !is Screen.Home) {
            nav.reset(Screen.Home)
        }
    }

    when (val screen = nav.current) {
        Screen.Onboarding -> OnboardingScreen(
            container = container,
            onDone = {
                container.prefs.onboardingDone = true
                nav.reset(Screen.Home)
            },
        )

        Screen.Home -> {
            val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
            HomeScreen(
                viewModel = vm,
                pendingJoinCode = pendingJoinCode,
                onEnterRoom = { code -> nav.push(Screen.Room(code)) },
                onOpenDiagnostics = { nav.push(Screen.Diagnostics) },
                onOpenSetup = { nav.push(Screen.Onboarding) },
            )
        }

        Screen.Diagnostics -> {
            val vm: DiagnosticsViewModel =
                viewModel(factory = viewModelFactory { initializer { DiagnosticsViewModel(container.gateway) } })
            DiagnosticsScreen(viewModel = vm, onBack = nav::pop)
        }

        is Screen.Room -> {
            val vm: RoomViewModel = viewModel(
                key = "room-${screen.code}",
                factory = viewModelFactory { initializer { RoomViewModel(screen.code, container) } },
            )
            RoomScreen(
                viewModel = vm,
                onLeft = { nav.reset(Screen.Home) },
            )
        }
    }
}
