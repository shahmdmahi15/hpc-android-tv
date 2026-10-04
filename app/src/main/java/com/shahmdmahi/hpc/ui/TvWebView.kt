package com.shahmdmahi.hpc.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shahmdmahi.hpc.R
import com.shahmdmahi.hpc.util.DeviceRoleManager
import com.shahmdmahi.hpc.util.HPCDownloadHelper
import com.shahmdmahi.hpc.util.HPCPrintHelper
import com.shahmdmahi.hpc.util.HPCTextToSpeechHelper
import com.shahmdmahi.hpc.util.NetworkScanner
import kotlinx.coroutines.delay

private const val TAG = "HPC_TvWebView"

@Composable
fun TvWebView(
    targetUrl: String = stringResource(id = R.string.pwa_target_url),
    onRescanRequested: (() -> Unit)? = null,
    onManualIpChanged: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val focusRequester = remember { FocusRequester() }
    val ttsHelper = remember { HPCTextToSpeechHelper(context) }

    val webView = remember(targetUrl) {
        createAndConfigureWebView(context, targetUrl, ttsHelper).apply {
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    Log.d(TAG, "Granting WebRTC permissions for origin: ${request?.origin}")
                    request?.grant(request.resources)
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val newWebView = WebView(view?.context ?: context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                req?.url?.toString()?.let { url -> view?.loadUrl(url) }
                                return true
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(
                                v: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                handler?.proceed()
                            }
                        }
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(context)
                        .setTitle("Alert")
                        .setMessage(message ?: "")
                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .create()
                        .show()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(context)
                        .setTitle("Confirm")
                        .setMessage(message ?: "")
                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .create()
                        .show()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    val input = EditText(context).apply {
                        setText(defaultValue ?: "")
                    }
                    AlertDialog.Builder(context)
                        .setTitle(message ?: "Prompt")
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            result?.confirm(input.text.toString())
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            result?.cancel()
                        }
                        .setOnCancelListener { result?.cancel() }
                        .create()
                        .show()
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    customView = view
                    customViewCallback = callback
                }

                override fun onHideCustomView() {
                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null
                }
            }

            webViewClient = object : WebViewClient() {
                private val ttsPolyfill = """
                    (function() {
                        try {
                            const bridge = window.HpcNative || window.AndroidTTS;
                            window.speechSynthesis = window.speechSynthesis || {};
                            if (typeof window.speechSynthesis.addEventListener !== 'function') {
                                window.speechSynthesis.addEventListener = function(event, callback) {
                                    if (event === 'voiceschanged' && typeof callback === 'function') {
                                        try { callback(); } catch(e) {}
                                    }
                                };
                            }
                            if (typeof window.speechSynthesis.removeEventListener !== 'function') {
                                window.speechSynthesis.removeEventListener = function() {};
                            }
                            if (typeof window.speechSynthesis.getVoices !== 'function') {
                                window.speechSynthesis.getVoices = function() {
                                    return [{ default: true, lang: 'en-US', localService: true, name: 'Android Native TTS Voice' }];
                                };
                            }
                            if (typeof window.speechSynthesis.cancel !== 'function') {
                                window.speechSynthesis.cancel = function() {
                                    if (bridge && typeof bridge.stop === 'function') bridge.stop();
                                };
                            }
                            if (typeof window.speechSynthesis.resume !== 'function') {
                                window.speechSynthesis.resume = function() {};
                            }
                            if (typeof window.speechSynthesis.pause !== 'function') {
                                window.speechSynthesis.pause = function() {};
                            }
                            if (bridge) {
                                window.speechSynthesis.speak = function(utterance) {
                                    if (utterance && utterance.text) {
                                        const lang = utterance.lang || 'en-US';
                                        const mode = lang.startsWith('bn') ? 'bn' : 'en';
                                        try {
                                            if (typeof utterance.onstart === 'function') utterance.onstart();
                                        } catch(e) {}
                                        if (typeof bridge.speakAnnouncement === 'function') {
                                            bridge.speakAnnouncement(utterance.text, utterance.text, mode);
                                        } else if (typeof bridge.speak === 'function') {
                                            bridge.speak(utterance.text, lang);
                                        }
                                        setTimeout(function() {
                                            try {
                                                if (typeof utterance.onend === 'function') utterance.onend();
                                            } catch(e) {}
                                        }, 4000);
                                    }
                                };
                            }
                        } catch(e) {
                            console.warn('[TTS Polyfill error]:', e);
                        }
                    })();
                """.trimIndent()

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    hasError = false
                    view?.evaluateJavascript(ttsPolyfill, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    view?.requestFocus()
                    view?.evaluateJavascript(ttsPolyfill, null)
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Always bypass SSL certificate errors for the private local hospital network
                    Log.w(TAG, "Proceeding through SSL error for local offline server: ${error?.url}")
                    handler?.proceed()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        isLoading = false
                        hasError = true
                        errorMessage = error?.description?.toString() ?: "Failed to connect to local server"
                        Log.e(TAG, "Main frame error: ${error?.errorCode} - ${error?.description}")
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }
        }
    }

    // Connection Timeout Safety Timer (25 seconds for cold SSR compile)
    LaunchedEffect(isLoading, targetUrl) {
        if (isLoading) {
            delay(25000)
            if (isLoading) {
                isLoading = false
                hasError = true
                errorMessage = "Connection timed out connecting to $targetUrl"
            }
        }
    }

    // Pass D-Pad focus to error button when error screen shows
    LaunchedEffect(hasError) {
        if (hasError) {
            focusRequester.requestFocus()
        }
    }

    DisposableEffect(targetUrl) {
        onDispose {
            CookieManager.getInstance().flush()
            ttsHelper.stop()
            webView.stopLoading()
            webView.destroy()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsHelper.shutdown()
        }
    }

    // Handle Android TV / Tablet Back Button for WebView History or double-back exit
    BackHandler(enabled = true) {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else if (context is android.app.Activity) {
            context.finish()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (customView != null) {
            AndroidView(
                factory = {
                    FrameLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        addView(
                            customView,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { webView },
                update = { view ->
                    if (view.url == null) {
                        view.loadUrl(targetUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isLoading && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting to HPC Local App...",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }
        }

        if (hasError) {
            Box(
                modifier = Modifier
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
                        text = "Unable to connect to Server",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Target: $targetUrl\nReason: $errorMessage\n\nPlease ensure your server is running on port 3000 in your local Wi-Fi / LAN network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row {
                        Button(
                            onClick = {
                                hasError = false
                                isLoading = true
                                webView.loadUrl(targetUrl)
                            },
                            modifier = Modifier.focusRequester(focusRequester)
                        ) {
                            Text("Retry Connection")
                        }

                        if (onRescanRequested != null) {
                            Spacer(modifier = Modifier.width(16.dp))
                            OutlinedButton(
                                onClick = onRescanRequested
                            ) {
                                Text("Rescan Network")
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))
                        OutlinedButton(
                            onClick = {
                                val input = EditText(context).apply {
                                    hint = "192.168.2.2"
                                }
                                AlertDialog.Builder(context)
                                    .setTitle("Enter Server IP")
                                    .setMessage("Type the IP address of your server running on port 3000:")
                                    .setView(input)
                                    .setPositiveButton("Connect") { _, _ ->
                                        val ip = input.text.toString().trim()
                                        if (ip.isNotEmpty()) {
                                            val url = if (ip.startsWith("http")) ip else "http://$ip:3000"
                                            NetworkScanner.saveServerUrl(context, url)
                                            val savedRole = DeviceRoleManager.getSavedRole(context)
                                            val fullUrl = DeviceRoleManager.buildFullTargetUrl(url, savedRole)
                                            if (onManualIpChanged != null) {
                                                onManualIpChanged(fullUrl)
                                            } else {
                                                hasError = false
                                                isLoading = true
                                                webView.loadUrl(fullUrl)
                                            }
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        ) {
                            Text("Enter IP Manually")
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createAndConfigureWebView(
    context: Context,
    url: String,
    ttsHelper: HPCTextToSpeechHelper
): WebView {
    val webViewInstance = WebView(context)
    return webViewInstance.apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Focus and TV D-Pad settings
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true

        // Register Native Printing Bridge
        addJavascriptInterface(HPCPrintHelper(context, webViewInstance), "AndroidPrinter")

        // Register Native Offline Text-To-Speech Engine Bridge (HpcNative & AndroidTTS)
        addJavascriptInterface(ttsHelper, "HpcNative")
        addJavascriptInterface(ttsHelper, "AndroidTTS")

        // Register Native File Download Listener (Excel .xlsx, .csv, .db, PDFs)
        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
            HPCDownloadHelper.handleDownload(context, downloadUrl, userAgent, contentDisposition, mimetype)
        }

        // Forward D-Pad key events cleanly
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        false
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        // Enable Cookies including third party cookies for PWA authentication & state
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webViewInstance, true)
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            allowFileAccess = true
            allowContentAccess = true

            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)

            cacheMode = WebSettings.LOAD_DEFAULT

            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 HPCNativeTV/1.0"
        }

        loadUrl(url)
    }
}
