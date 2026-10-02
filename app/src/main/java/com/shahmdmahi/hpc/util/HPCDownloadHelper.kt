package com.shahmdmahi.hpc.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

object HPCDownloadHelper {
    private const val TAG = "HPC_DownloadHelper"

    fun handleDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        try {
            if (url.startsWith("data:")) {
                // Handle Base64 data URL downloads (e.g. dynamically generated Excel/CSV/Backup files)
                saveBase64DataUrl(context, url, mimetype)
            } else {
                // Handle standard HTTP/HTTPS downloads via DownloadManager
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Downloading HPC Clinical File...")
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    setTitle(filename)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(context, "Downloading file...", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Download enqueued via DownloadManager for: $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling file download", e)
            Toast.makeText(context, "Failed to download file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBase64DataUrl(context: Context, dataUrl: String, mimetype: String?) {
        try {
            val parts = dataUrl.split(",")
            if (parts.size < 2) return

            val header = parts[0]
            val base64Data = parts[1]
            val extension = when {
                mimetype?.contains("excel") == true || mimetype?.contains("spreadsheet") == true -> "xlsx"
                mimetype?.contains("csv") == true -> "csv"
                mimetype?.contains("pdf") == true -> "pdf"
                header.contains("xlsx") -> "xlsx"
                header.contains("csv") -> "csv"
                else -> "bin"
            }

            val fileName = "HPC_Export_${System.currentTimeMillis()}.$extension"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

            val fileData = Base64.decode(base64Data, Base64.DEFAULT)
            FileOutputStream(file).use { output ->
                output.write(fileData)
            }

            Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            Log.i(TAG, "Base64 data URL saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding Base64 data URL", e)
            Toast.makeText(context, "Failed to save export file", Toast.LENGTH_SHORT).show()
        }
    }
}
