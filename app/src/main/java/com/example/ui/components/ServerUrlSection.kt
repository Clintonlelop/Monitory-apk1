package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.MainViewModel

/**
 * Server address configuration block: enter / edit / validate / save the
 * management server URL, see the currently configured address, and test
 * backend connectivity before pairing or signing in.
 */
@Composable
fun ServerUrlSection(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val serverUrl by viewModel.serverUrl.collectAsState()
    val serverUrlError by viewModel.serverUrlError.collectAsState()
    val isServerConfigured by viewModel.isServerConfigured.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { viewModel.setServerUrl(it) },
            label = { Text("Management Server URL") },
            placeholder = { Text("https://monitory.example.com or http://192.168.1.10:3000") },
            leadingIcon = {
                Icon(Icons.Default.Dns, contentDescription = "Server URL")
            },
            singleLine = true,
            isError = serverUrlError != null,
            supportingText = {
                Text(
                    text = serverUrlError
                        ?: "API, WebSocket and dashboard addresses are derived from this server address.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("server_url_input"),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isServerConfigured && serverUrlError == null) {
                "Configured server: ${serverUrl.trim().removeSuffix("/")}"
            } else if (serverUrl.isBlank()) {
                "No server configured yet. Enter your Monitory backend address to continue."
            } else {
                "Not saved yet — fix the address above."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (isServerConfigured && serverUrlError == null) {
                Color(0xFF16A34A)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("configured_server_label")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.testServerConnection() },
            enabled = connectionState !is MainViewModel.ConnectionUiState.Testing,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("test_connection_button")
        ) {
            when (val state = connectionState) {
                is MainViewModel.ConnectionUiState.Testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing connection…")
                }
                else -> {
                    Icon(Icons.Default.WifiTethering, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Test Connection")
                }
            }
        }

        when (val state = connectionState) {
            is MainViewModel.ConnectionUiState.Testing -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Contacting server…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is MainViewModel.ConnectionUiState.Success -> {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF16A34A),
                        modifier = Modifier.testTag("connection_success")
                    )
                }
            }
            is MainViewModel.ConnectionUiState.Error -> {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("connection_error")
                    )
                }
            }
            else -> {}
        }
    }
}
