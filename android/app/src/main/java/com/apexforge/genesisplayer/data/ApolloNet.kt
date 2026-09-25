package com.apexforge.genesisplayer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "GenesisPlayer"

/**
 * Shared networking for the Apollo machine service (APOLLO-LIVE.md §2).
 *
 * Endpoints live on the tailnet only:
 *   POST https://zane-box-1.tail63e556.ts.net:18801/apollo/chat
 *   GET  https://zane-box-1.tail63e556.ts.net:18801/apollo/drops
 *   POST https://zane-box-1.tail63e556.ts.net:18801/apollo/decide   (proposed — see contract note)
 *   POST https://zane-box-1.tail63e556.ts.net:18801/history
 *
 * The tailnet serve cert comes from the tailnet's internal CA, which Android
 * does not trust by default. Like TasteSync, this uses a trust manager scoped
 * to the single hardcoded tailnet host — the WireGuard tailnet (authenticated
 * peers, encrypted transport) is the auth boundary. Never used for any other
 * host. There are no accounts, no tokens, no logins.
 */
object ApolloNet {
    const val HOST = "zane-box-1.tail63e556.ts.net"
    const val BASE = "https://$HOST:18801"
    const val CHAT_URL = "$BASE/apollo/chat"
    const val DROPS_URL = "$BASE/apollo/drops"
    const val DECIDE_URL = "$BASE/apollo/decide"
    const val HISTORY_URL = "$BASE/history"

    /** Public zero-auth fallback feed for drops (APOLLO-LIVE.md §2.2). */
    const val PUBLIC_SUGGESTIONS_URL =
        "https://raw.githubusercontent.com/brandonduda8/genesis-catalog/master/apollo_suggestions.json"

    private fun tailnetSsl(): SSLContext {
        val permissive = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(permissive), SecureRandom())
        }
    }

    private fun open(url: String, connectMs: Int, readMs: Int): HttpsURLConnection {
        val c = URL(url).openConnection() as HttpsURLConnection
        if (URL(url).host == HOST) {
            c.sslSocketFactory = tailnetSsl().socketFactory
        }
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        return c
    }

    /** POST JSON; returns (httpCode, body-or-null). -1 = transport failure. */
    fun postJson(url: String, body: String, connectMs: Int = 4000, readMs: Int = 8000): Pair<Int, String?> {
        return try {
            val c = open(url, connectMs, readMs).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "BrknVibes/1.1")
                doOutput = true
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val payload = try {
                (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            c.disconnect()
            code to payload
        } catch (e: Exception) {
            Log.i(TAG, "ApolloNet: POST $url failed (${e.javaClass.simpleName}: ${e.message})")
            -1 to null
        }
    }

    /** GET; returns (httpCode, body-or-null). -1 = transport failure. */
    fun get(url: String, connectMs: Int = 4000, readMs: Int = 8000): Pair<Int, String?> {
        return try {
            val c = open(url, connectMs, readMs).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BrknVibes/1.1")
            }
            val code = c.responseCode
            val payload = try {
                (if (code == 200) c.inputStream else null)
                    ?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) { null }
            c.disconnect()
            code to payload
        } catch (e: Exception) {
            Log.i(TAG, "ApolloNet: GET $url failed (${e.javaClass.simpleName}: ${e.message})")
            -1 to null
        }
    }

    /** Any internet at all (not tailnet-specific). */
    fun isInternetUp(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) { false }
    }

    fun isOnWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) { false }
    }
}
