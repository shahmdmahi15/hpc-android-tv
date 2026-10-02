package com.shahmdmahi.hpc.util

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

class HPCPrintHelper(private val context: Context, private val webView: WebView) {

    companion object {
        private const val TAG = "HPC_PrintHelper"

        fun printWebView(context: Context, webView: WebView, jobName: String = "HPC_Clinical_Prescription") {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager != null) {
                    val printAdapter = webView.createPrintDocumentAdapter(jobName)
                    val customMediaSize = PrintAttributes.MediaSize("HPC_55x827", "HPC 5.5x8.27in", 5500, 8270)
                    val printAttributes = PrintAttributes.Builder()
                        .setMediaSize(customMediaSize)
                        .setResolution(PrintAttributes.Resolution("HPC_RES", "High Quality", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                    printManager.print(jobName, printAdapter, printAttributes)
                    Log.i(TAG, "Sent 5.5\"x8.27\" print job to PrintManager: $jobName")
                } else {
                    Log.e(TAG, "PrintManager not available on this device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating WebView print job", e)
            }
        }
    }

    @JavascriptInterface
    fun print() {
        webView.post {
            printWebView(context, webView, "HPC_Clinical_Prescription")
        }
    }
}
