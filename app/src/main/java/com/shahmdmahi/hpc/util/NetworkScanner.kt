package com.shahmdmahi.hpc.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetworkScanner {
    private const val TAG = "HPC_NetworkScanner"
    private const val PREFS_NAME = "hpc_server_prefs"
    private const val KEY_SERVER_URL = "saved_server_url"
    private const val CERT_FILE_NAME = "downloaded_root_ca.pem"

    fun getSavedServerUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, null)
    }

    fun saveServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun clearSavedServerUrl(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SERVER_URL).apply()
    }

    fun getDownloadedCertFile(context: Context): File {
        return File(context.filesDir, CERT_FILE_NAME)
    }

    /**
     * Gets the local device IPv4 address (Ethernet or Wi-Fi).
     */
    fun getLocalDeviceIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining local IP address", e)
        }
        return null
    }

    /**
     * Multi-subnet Scanner:
     * First checks top priority IPs (e.g. 192.168.2.2, 192.168.1.2, 192.168.10.124, 192.168.3.2).
     * Then scans the primary active subnet, and falls back to scanning common private subnets.
     */
    suspend fun scanLocalSubnetForServer(
        port: Int = 3000,
        timeoutMs: Int = 600,
        onProgress: (scannedCount: Int, total: Int, currentIp: String) -> Unit = { _, _, _ -> }
    ): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalDeviceIp() ?: "192.168.2.100"
        val primarySubnet = localIp.substringBeforeLast(".")

        Log.i(TAG, "Starting multi-subnet scan for port $port (Primary device IP: $localIp)")

        // 1. Check top priority specific IPs across common local subnets first (Parallel Fast Probe)
        val priorityIps = listOf(
            "$primarySubnet.2",
            "192.168.2.2",
            "192.168.1.2",
            "192.168.10.124",
            "192.168.3.2",
            "192.168.0.2",
            "192.168.100.2",
            "$primarySubnet.1",
            "$primarySubnet.100",
            "$primarySubnet.254",
            "10.0.0.2"
        ).distinct()

        Log.d(TAG, "Probing priority candidate IPs: $priorityIps")
        val priorityMatch = coroutineScope {
            val deferreds = priorityIps.map { ip ->
                async {
                    if (isPortOpen(ip, port, timeoutMs)) ip else null
                }
            }
            deferreds.awaitAll().filterNotNull().firstOrNull()
        }

        if (priorityMatch != null) {
            val resolvedUrl = determineServerProtocol(priorityMatch, port)
            Log.i(TAG, "Priority match found: $resolvedUrl")
            return@withContext resolvedUrl
        }

        // 2. Build multi-subnet list (Primary subnet first, followed by other common subnets)
        val targetSubnets = listOf(
            primarySubnet,
            "192.168.2",
            "192.168.1",
            "192.168.10",
            "192.168.3",
            "192.168.0",
            "192.168.100",
            "10.0.0"
        ).distinct()

        var totalScanned = priorityIps.size
        val totalToScan = targetSubnets.size * 254

        for (subnet in targetSubnets) {
            Log.d(TAG, "Scanning subnet: $subnet.*")
            val hosts = (1..254).map { "$subnet.$it" }.filter { !priorityIps.contains(it) }

            val batchSize = 35
            for (batch in hosts.chunked(batchSize)) {
                val foundIp = coroutineScope {
                    val deferreds = batch.map { ip ->
                        async {
                            totalScanned++
                            onProgress(totalScanned, totalToScan, ip)
                            if (isPortOpen(ip, port, timeoutMs)) ip else null
                        }
                    }
                    deferreds.awaitAll().filterNotNull().firstOrNull()
                }

                if (foundIp != null) {
                    val resolvedUrl = determineServerProtocol(foundIp, port)
                    Log.i(TAG, "Server discovered: $resolvedUrl")
                    return@withContext resolvedUrl
                }
            }
        }

        null
    }

    /**
     * Determines whether the server on host:port speaks HTTPS or HTTP.
     */
    fun determineServerProtocol(ip: String, port: Int): String {
        // Test HTTPS first
        if (testHttpEndpoint("https://$ip:$port/api/health") || testHttpEndpoint("https://$ip:$port/_hpc_health")) {
            return "https://$ip:$port"
        }
        // Test HTTP
        if (testHttpEndpoint("http://$ip:$port/api/health") || testHttpEndpoint("http://$ip:$port/_hpc_health")) {
            return "http://$ip:$port"
        }
        // Default to https
        return "https://$ip:$port"
    }

    private fun testHttpEndpoint(endpoint: String): Boolean {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection()
            conn.connectTimeout = 800
            conn.readTimeout = 800
            if (conn is HttpsURLConnection) {
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), java.security.SecureRandom())
                conn.sslSocketFactory = sc.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            val code = (conn as? java.net.HttpURLConnection)?.responseCode ?: -1
            code in 200..404
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Attempts a socket connection to host:port.
     */
    fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a server URL is alive on port 3000.
     */
    suspend fun isServerReachable(url: String, timeoutMs: Int = 1200): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return@withContext false
            val port = if (uri.port != -1) uri.port else 3000
            isPortOpen(host, port, timeoutMs)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetches rootCA.pem or rootCA.crt from server at https://<host>:<port>/rootCA.pem or /rootCA.crt.
     */
    suspend fun fetchAndSaveRootCaCertificate(
        context: Context,
        serverUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val httpUrl = serverUrl.replace("https://", "http://")
        val certUrlCandidates = listOf(
            "$serverUrl/rootCA.pem",
            "$serverUrl/rootCA.crt",
            "$httpUrl/rootCA.pem",
            "$httpUrl/rootCA.crt"
        )

        for (candidate in certUrlCandidates) {
            try {
                Log.d(TAG, "Attempting to download Root CA certificate from $candidate")
                val certBytes = downloadCertificateBytes(candidate)
                if (certBytes != null && certBytes.isNotEmpty()) {
                    // Validate certificate format (X.509 handles both PEM and binary CRT)
                    val cf = CertificateFactory.getInstance("X.509")
                    val ca = cf.generateCertificate(certBytes.inputStream()) as X509Certificate
                    Log.i(TAG, "Downloaded valid Root CA certificate from $candidate for: ${ca.subjectDN}")

                    // Save certificate to app internal storage
                    val certFile = getDownloadedCertFile(context)
                    certFile.writeBytes(certBytes)
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching certificate from $candidate: ${e.message}")
            }
        }
        false
    }

    private fun downloadCertificateBytes(urlString: String): ByteArray? {
        var inputStream: InputStream? = null
        try {
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            if (connection is HttpsURLConnection) {
                // Trust self-signed certificates temporarily during rootCA.pem download
                val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                )
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, trustAllCerts, java.security.SecureRandom())
                connection.sslSocketFactory = sc.socketFactory
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            inputStream = connection.getInputStream()
            return inputStream.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading cert from $urlString", e)
            return null
        } finally {
            inputStream?.close()
        }
    }
}
