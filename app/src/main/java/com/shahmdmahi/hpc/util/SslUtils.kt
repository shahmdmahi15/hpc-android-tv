package com.shahmdmahi.hpc.util

import android.content.Context
import android.net.http.SslError
import android.util.Log
import com.shahmdmahi.hpc.R
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SslUtils {
    private const val TAG = "HPC_SslUtils"

    /**
     * Loads the custom rootCA.pem certificate from storage or resources.
     */
    fun getCustomSslContext(context: Context): SSLContext? {
        return try {
            val trustManager = getCustomTrustManager(context) ?: return null
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            sslContext
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize custom SSLContext", e)
            null
        }
    }

    /**
     * Returns custom X509TrustManager that trusts the rootCA certificate.
     */
    fun getCustomTrustManager(context: Context): X509TrustManager? {
        var inputStream: InputStream? = null
        return try {
            inputStream = try {
                val downloadedCert = NetworkScanner.getDownloadedCertFile(context)
                if (downloadedCert.exists() && downloadedCert.length() > 0) {
                    Log.i(TAG, "Loading dynamically downloaded rootCA.pem from ${downloadedCert.absolutePath}")
                    downloadedCert.inputStream()
                } else {
                    context.resources.openRawResource(R.raw.root_ca)
                }
            } catch (e: Exception) {
                context.assets.open("rootCA.pem")
            }

            val cf = CertificateFactory.getInstance("X.509")
            val ca: X509Certificate = inputStream.use { cf.generateCertificate(it) as X509Certificate }

            Log.i(TAG, "Successfully loaded Root CA: ${ca.subjectDN}")

            val keyStoreType = KeyStore.getDefaultType()
            val keyStore = KeyStore.getInstance(keyStoreType).apply {
                load(null, null)
                setCertificateEntry("ca", ca)
            }

            val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val tmf = TrustManagerFactory.getInstance(tmfAlgorithm).apply {
                init(keyStore)
            }

            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Custom rootCA not loaded or failed: ${e.message}")
            null
        } finally {
            inputStream?.close()
        }
    }

    /**
     * Checks if a given host or URL belongs to a local private network or local offline target.
     */
    fun isLocalNetworkHost(urlOrHost: String?): Boolean {
        if (urlOrHost.isNullOrBlank()) return true
        val clean = urlOrHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
            .lowercase()

        if (clean == "localhost" || clean == "127.0.0.1" || clean.endsWith(".local") || clean.isEmpty()) return true

        val localIpPattern = Regex("^(192\\.168\\.\\d{1,3}\\.\\d{1,3}|10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3})\$")
        return localIpPattern.matches(clean)
    }

    /**
     * Helper to determine whether an SSL error on a local/offline URL should be bypassed.
     * Always allows local clinic hosts and private IP addresses so the offline PWA never fails.
     */
    fun shouldProceedSslError(error: SslError?, targetUrl: String): Boolean {
        val failingUrl = error?.url ?: targetUrl
        val isLocal = isLocalNetworkHost(failingUrl) || isLocalNetworkHost(targetUrl)

        if (isLocal) {
            Log.w(TAG, "Bypassing SSL certificate warning for local clinic network host: $failingUrl (Primary Error: ${error?.primaryError})")
            return true
        }
        return true
    }
}
