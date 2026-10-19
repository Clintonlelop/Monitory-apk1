package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.DeviceManagerApp
import com.example.diagnostics.CrashDiagnostics
import com.example.diagnostics.CrashReportStore
import java.io.File

/**
 * Crash diagnostics panel: inspect, copy, share/export and delete the full
 * diagnostic reports (complete nested stack traces, redacted secrets) and
 * capture an on-demand snapshot of the current state.
 */
@Composable
fun CrashDiagnosticsCard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var reports by remember { mutableStateOf(emptyList<File>()) }
    var viewedReport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var snapshotMessage by remember { mutableStateOf<String?>(null) }

    val store = remember { CrashReportStore(context) }
    fun refresh() { reports = store.list() }
    LaunchedEffect(Unit) { refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Crash Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "When something fails, a full diagnostic report (complete nested stack traces, device & build info, pairing/server state) is saved locally. Secrets are redacted. Reports stay on this device unless you share one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val report = try {
                            // Throw-and-catch to capture the current stack with
                            // full environment context as a manual snapshot.
                            throw IllegalStateException("Manual diagnostic snapshot")
                        } catch (e: Exception) {
                            CrashDiagnostics.buildReport(
                                context = context,
                                throwable = e,
                                operation = "manual diagnostic snapshot",
                                connectionState = if ((context.applicationContext as DeviceManagerApp).webSocketManager.isConnected.value) "websocket connected" else "websocket disconnected"
                            )
                        }
                        store.write(report)
                        refresh()
                        snapshotMessage = "Snapshot saved (${reports.size + 1} report(s) available)"
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("save_snapshot_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Snapshot", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        val count = store.clear()
                        refresh()
                        snapshotMessage = "Deleted $count report(s)"
                    },
                    enabled = reports.isNotEmpty(),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("clear_reports_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", fontSize = 13.sp)
                }
            }

            snapshotMessage?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (reports.isEmpty()) {
                Text(
                    text = "No diagnostic reports saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("no_reports_label")
                )
            } else {
                reports.take(8).forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("report_item_${file.name}")
                        )
                        TextButton(onClick = {
                            viewedReport = file.name to store.read(file)
                        }) {
                            Text("View", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    viewedReport?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { viewedReport = null },
            title = { Text(name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(content))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        val file = store.list().firstOrNull { it.name == name }
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Monitory diagnostic report")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Export diagnostic report"))
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", fontSize = 12.sp)
                    }
                    TextButton(onClick = { viewedReport = null }) {
                        Text("Close", fontSize = 12.sp)
                    }
                }
            }
        )
    }
}
