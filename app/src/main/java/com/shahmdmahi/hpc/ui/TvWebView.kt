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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shahmdmahi.hpc.R
import com.shahmdmahi.hpc.util.SslUtils

private const val TAG = "HPC_TvWebView"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWebView(
    targetUrl: String = stringResource(id = R.string.pwa_target_url),
    onRescanRequested: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val webView = remember {
        createAndConfigureWebView(context, targetUrl).apply {
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
                    val newWebView = WebView(view?.context ?: context)
                    newWebView.settings.javaScriptEnabled = true
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                            req?.url?.toString()?.let { url -> view?.loadUrl(url) }
                            return true
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    hasError = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isLoading = false
                    // Ensure focus for TV D-Pad navigation
                    view?.requestFocus()
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    if (SslUtils.shouldProceedSslError(error, targetUrl)) {
                        handler?.proceed()
                    } else {
                        super.onReceivedSslError(view, handler, error)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Only show error screen for main frame loading failures
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
                    // Keep navigation inside WebView for PWA experience
                    return false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    // Handle Android TV Back Button for WebView History or Custom View exit
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
                    if (view.url == null || hasError) {
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
                        text = "Loading Local PWA App...",
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
                        text = "Target: $targetUrl\nReason: $errorMessage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    androidx.compose.foundation.layout.Row {
                        Button(
                            onClick = {
                                hasError = false
                                isLoading = true
                                webView.loadUrl(targetUrl)
                            }
                        ) {
                            Text("Retry Connection")
                        }

                        if (onRescanRequested != null) {
                            Spacer(modifier = Modifier.height(0.dp))
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                            androidx.tv.material3.OutlinedButton(
                                onClick = onRescanRequested
                            ) {
                                Text("Rescan Network")
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createAndConfigureWebView(context: Context, url: String): WebView {
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

        // Forward D-Pad key events cleanly
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        // Pass enter/click to focused web element
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

            // Mixed Content Mode for local network resources
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            allowFileAccess = true
            allowContentAccess = true

            // Viewport settings for desktop/TV PWA rendering
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)

            // Cache & Service Worker setup for offline PWA
            cacheMode = WebSettings.LOAD_DEFAULT

            // Modern Desktop Chrome TV User-Agent to prevent mobile fallback layout
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 HPCNativeTV/1.0"
        }

        loadUrl(url)
    }
}
