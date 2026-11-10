package com.campusmarket.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.campusmarket.app.data.ApiClient
import com.campusmarket.app.data.ApiError
import com.campusmarket.app.data.ServerConfig
import com.campusmarket.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Debug-only: point the app at another server (e.g. your laptop's LAN IP from a real phone).
 * Shown from Welcome → "Server" and from Settings. Does nothing useful in release builds (override is ignored).
 */
@Composable
fun ServerSettingsDialog(onDismiss: () -> Unit) {
    val server: ServerConfig = koinInject()
    val api: ApiClient = koinInject()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(server.baseUrl.value) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppTextField(
                    value = text,
                    onValueChange = { text = it; status = null },
                    label = "Base URL",
                    supportingText = "Default: ${server.defaultBaseUrl}",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    enabled = !testing,
                    onClick = {
                        server.setOverride(text)
                        text = server.baseUrl.value
                        testing = true
                        scope.launch {
                            status = try {
                                api.health()
                                "Connected to ${server.baseUrl.value}"
                            } catch (e: ApiError) {
                                "Failed: ${e.message}"
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Save & test connection") }
            }
        },
        confirmButton = {
            TextButton(onClick = { server.setOverride(text); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { server.setOverride(null); onDismiss() }) { Text("Reset to default") }
        },
    )
}
