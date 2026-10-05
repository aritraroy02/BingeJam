package com.syncit.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    pendingJoinCode: MutableState<String?>,
    onEnterRoom: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    // Pre-fill from a syncit://join/CODE link.
    LaunchedEffect(pendingJoinCode.value) {
        pendingJoinCode.value?.let {
            viewModel.onCodeChange(it)
            pendingJoinCode.value = null
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sync it", style = MaterialTheme.typography.headlineMedium)

            OutlinedTextField(
                value = ui.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { viewModel.create(onEnterRoom) },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create a room") }

            HorizontalDivider()

            Text("Join a friend's room", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ui.code,
                onValueChange = viewModel::onCodeChange,
                label = { Text("6-character room code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.join(onJoined = onEnterRoom) },
                enabled = !ui.busy && ui.codeValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join room") }

            ui.lastRoom?.let { last ->
                OutlinedButton(
                    onClick = { viewModel.join(code = last, onJoined = onEnterRoom) },
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Rejoin room $last") }
            }

            if (ui.busy) CircularProgressIndicator()
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenDiagnostics) { Text("Media diagnostics") }
                TextButton(onClick = onOpenSetup) { Text("Setup & permissions") }
            }
        }
    }
}
