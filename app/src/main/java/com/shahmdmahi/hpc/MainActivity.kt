package com.shahmdmahi.hpc

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.shahmdmahi.hpc.ui.ServerDiscoveryScreen
import com.shahmdmahi.hpc.ui.TvWebView
import com.shahmdmahi.hpc.ui.theme.HPCTheme
import com.shahmdmahi.hpc.util.DeviceRoleManager
import com.shahmdmahi.hpc.util.NetworkScanner

class MainActivity : ComponentActivity() {

    private var backPressedTime = 0L

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions granted/denied handled gracefully by WebChromeClient
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock screen on and hide system UI for true TV / Waiting Room Kiosk fullscreen experience
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Double-tap Back to exit handling (prevents accidental staff logout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    finish()
                } else {
                    Toast.makeText(this@MainActivity, "Press back again to exit HPC Clinical App", Toast.LENGTH_SHORT).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        })

        // Request WebRTC runtime permissions (Audio & Camera)
        requestPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
            )
        )

        setContent {
            HPCTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    val savedBaseUrl = remember { NetworkScanner.getSavedServerUrl(this@MainActivity) }
                    val savedRole = remember { DeviceRoleManager.getSavedRole(this@MainActivity) }

                    val savedFullUrl = remember(savedBaseUrl, savedRole) {
                        if (!savedBaseUrl.isNullOrEmpty()) {
                            DeviceRoleManager.buildFullTargetUrl(savedBaseUrl, savedRole)
                        } else null
                    }

                    var activeServerUrl by remember { mutableStateOf<String?>(savedFullUrl) }
                    var isCheckingSavedUrl by remember { mutableStateOf(!savedBaseUrl.isNullOrEmpty()) }

                    // Health check: Verify saved URL is still alive when launching app
                    LaunchedEffect(savedBaseUrl) {
                        if (!savedBaseUrl.isNullOrEmpty()) {
                            val isAlive = NetworkScanner.isServerReachable(savedBaseUrl, timeoutMs = 1200)
                            if (!isAlive) {
                                Log.w("HPC_MainActivity", "Saved server $savedBaseUrl is unreachable. Auto-rescanning network...")
                                NetworkScanner.clearSavedServerUrl(this@MainActivity)
                                activeServerUrl = null
                            }
                            isCheckingSavedUrl = false
                        }
                    }

                    if (isCheckingSavedUrl) {
                        // Quick check indicator while verifying previous URL
                        ServerDiscoveryScreen(
                            onServerDiscovered = { discoveredUrl ->
                                activeServerUrl = discoveredUrl
                                isCheckingSavedUrl = false
                            }
                        )
                    } else if (activeServerUrl.isNullOrEmpty()) {
                        ServerDiscoveryScreen(
                            onServerDiscovered = { discoveredUrl ->
                                activeServerUrl = discoveredUrl
                            }
                        )
                    } else {
                        TvWebView(
                            targetUrl = activeServerUrl!!,
                            onRescanRequested = {
                                NetworkScanner.clearSavedServerUrl(this@MainActivity)
                                activeServerUrl = null
                            },
                            onManualIpChanged = { newUrl ->
                                activeServerUrl = newUrl
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
