package com.syncit.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.SyncDots
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.core.social.BirthDateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Asks everyone who signed up before dates of birth were required (2026-11-10). */
class BirthdayGateViewModel(container: AppContainer) : ViewModel() {
    private val profiles = container.social.profiles

    val state: StateFlow<BirthDateState> = profiles.observeMyBirthDate()
        .catch { emit(BirthDateState.Loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BirthDateState.Loading)

    private val _picked = MutableStateFlow<LocalDate?>(null)
    val picked: StateFlow<LocalDate?> = _picked.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun pick(date: LocalDate) {
        _picked.value = date
        _error.value = null
    }

    fun save() {
        val date = _picked.value?.takeIf { BirthDates.problem(it) == null } ?: return
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            try {
                profiles.setBirthDate(BirthDates.wire(date))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "That didn't save. Check your connection and try again."
            } finally {
                _saving.value = false
            }
        }
    }
}

/**
 * The card over everything until a date of birth is saved. It can't be dismissed: no close button,
 * Back does nothing, taps on the dimmed app behind it are swallowed.
 */
@Composable
fun BirthdayGate(viewModel: BirthdayGateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val show = state == BirthDateState.Missing
    BackHandler(enabled = show) {}
    AnimatedVisibility(visible = show, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        val p = Glass.palette
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = true, enter = scaleIn(tween(260), initialScale = 0.92f)) {
                Column(
                    Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .glass(floating = true)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SyncDots(size = 10.dp)
                    Text("Sorry to barge in mid-binge", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
                    Text(
                        "We forgot to ask one tiny thing when you joined: when's your birthday? Tell us once and we'll " +
                            "make sure the day doesn't go by quietly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                    BirthDateField(picked, onPick = viewModel::pick)
                    Text(
                        "It stays private. Your friends never see it, and no, you can't skip this one. We did ask nicely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                    )
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
                    GlassButton(
                        "That's my birthday",
                        onClick = viewModel::save,
                        enabled = picked?.let { BirthDates.problem(it) } == null && picked != null && !saving,
                        loading = saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
