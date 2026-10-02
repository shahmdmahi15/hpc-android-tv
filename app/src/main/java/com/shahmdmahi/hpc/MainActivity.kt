package com.shahmdmahi.hpc

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
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
import com.shahmdmahi.hpc.util.NetworkScanner

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions granted/denied handled gracefully by WebChromeClient
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock screen on and hide system UI for true TV fullscreen experience
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

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
                    var activeServerUrl by remember {
                        mutableStateOf(NetworkScanner.getSavedServerUrl(this@MainActivity))
                    }

                    if (activeServerUrl.isNullOrEmpty()) {
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
