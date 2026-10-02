package com.shahmdmahi.hpc.ui

import android.app.AlertDialog
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shahmdmahi.hpc.R
import com.shahmdmahi.hpc.util.DeviceRole
import com.shahmdmahi.hpc.util.DeviceRoleManager
import com.shahmdmahi.hpc.util.NetworkScanner
import kotlinx.coroutines.launch

@Composable
fun ServerDiscoveryScreen(
    onServerDiscovered: (serverUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultUrl = stringResource(id = R.string.pwa_target_url)

    var selectedRole by remember { mutableStateOf(DeviceRoleManager.getSavedRole(context)) }
    var isScanning by remember { mutableStateOf(true) }
    var currentIp by remember { mutableStateOf("") }
    var scannedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(254) }
    var statusText by remember { mutableStateOf("Scanning local network for port 3000...") }
    var searchFailed by remember { mutableStateOf(false) }

    val primaryButtonFocusRequester = remember { FocusRequester() }

    fun completeDiscovery(baseUrl: String) {
        val fullUrl = DeviceRoleManager.buildFullTargetUrl(baseUrl, selectedRole)
        NetworkScanner.saveServerUrl(context, baseUrl)
        DeviceRoleManager.saveRole(context, selectedRole)
        onServerDiscovered(fullUrl)
    }

    fun startScan() {
        isScanning = true
        searchFailed = false
        scannedCount = 0
        statusText = "Searching local network for HPC server on port 3000..."

        scope.launch {
            val discoveredUrl = NetworkScanner.scanLocalSubnetForServer(
                port = 3000,
                onProgress = { count, total, ip ->
                    scannedCount = count
                    totalCount = total
                    currentIp = ip
                }
            )

            if (discoveredUrl != null) {
                statusText = "Found server at $discoveredUrl! Fetching Root CA..."
                val certFetched = NetworkScanner.fetchAndSaveRootCaCertificate(context, discoveredUrl)
                if (certFetched) {
                    statusText = "Root CA installed successfully! Launching PWA..."
                }
                completeDiscovery(discoveredUrl)
            } else {
                isScanning = false
                searchFailed = true
                statusText = "No server found automatically on port 3000."
            }
        }
    }

    LaunchedEffect(Unit) {
        startScan()
    }

    // Move D-Pad Focus to Rescan button when search fails
    LaunchedEffect(searchFailed) {
        if (searchFailed) {
            primaryButtonFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "HPC Clinical System Setup",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Selected Role: ${selectedRole.title} (${selectedRole.path})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.LightGray
                )
                if (currentIp.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Checking ($scannedCount/$totalCount): $currentIp:3000",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            } else if (searchFailed) {
                Text(
                    text = "Server Not Found Automatically",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Please ensure your server is running on port 3000 in your local network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row {
                    Button(
                        onClick = { startScan() },
                        modifier = Modifier.focusRequester(primaryButtonFocusRequester)
                    ) {
                        Text("Rescan Network")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = {
                            val roleItems = DeviceRole.entries.map { it.title }.toTypedArray()
                            AlertDialog.Builder(context)
                                .setTitle("Select Terminal Role")
                                .setItems(roleItems) { _, which ->
                                    selectedRole = DeviceRole.entries[which]
                                    DeviceRoleManager.saveRole(context, selectedRole)
                                }
                                .show()
                        }
                    ) {
                        Text("Change Terminal Role")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = {
                            val input = EditText(context).apply {
                                hint = "192.168.10.124"
                            }
                            AlertDialog.Builder(context)
                                .setTitle("Enter Server IP")
                                .setMessage("Type the IP address of your server (e.g. 192.168.10.124):")
                                .setView(input)
                                .setPositiveButton("Connect") { _, _ ->
                                    val ip = input.text.toString().trim()
                                    if (ip.isNotEmpty()) {
                                        val url = if (ip.startsWith("http")) ip else "https://$ip:3000"
                                        scope.launch {
                                            NetworkScanner.fetchAndSaveRootCaCertificate(context, url)
                                            completeDiscovery(url)
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    ) {
                        Text("Enter IP Manually")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                NetworkScanner.fetchAndSaveRootCaCertificate(context, defaultUrl)
                                completeDiscovery(defaultUrl)
                            }
                        }
                    ) {
                        Text("Use Default Config URL")
                    }
                }
            }
        }
    }
}
