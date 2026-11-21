package com.examscan.scan

import android.annotation.SuppressLint
import android.content.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Requests spaced beyond the foreground four-scans/two-minutes window; no association or probing hosts. */
class WifiScanner(context: Context, private val intervalMs: Long = 35_000) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    @SuppressLint("MissingPermission")
    fun scan(status: (ChannelStatus) -> Unit = {}): Flow<List<WifiSignal>> = callbackFlow {
        val manager = wifi ?: run { close(IllegalStateException("Wi-Fi unavailable")); return@callbackFlow }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    status(if (updated) ChannelStatus.SCANNING else ChannelStatus.THROTTLED)
                    trySend(currentResults(manager))
                } catch (e: SecurityException) { close(e) }
            }
        }
        // This system broadcast can come from a privileged Wi-Fi process, not only system UID.
        ContextCompat.registerReceiver(appContext, receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        val loop = launch {
            try {
                trySend(currentResults(manager))
                while (isActive) {
                    @Suppress("DEPRECATION")
                    val accepted = manager.startScan()
                    status(if (accepted) ChannelStatus.WAITING else ChannelStatus.THROTTLED)
                    delay(intervalMs)
                }
            } catch (e: Exception) { close(e) }
        }
        awaitClose {
            loop.cancel()
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentResults(manager: WifiManager): List<WifiSignal> {
        val wall = System.currentTimeMillis()
        val bootUs = SystemClock.elapsedRealtimeNanos() / 1000
        return manager.scanResults.mapNotNull { result ->
            if (result.timestamp <= 0 || result.timestamp > bootUs + 1_000_000) return@mapNotNull null
            val raw = if (Build.VERSION.SDK_INT >= 33) result.wifiSsid?.toString()
                else @Suppress("DEPRECATION") result.SSID
            val ssid = raw?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            WifiSignal(result.BSSID.uppercase(), ssid, result.level, result.frequency,
                wall - ((bootUs - result.timestamp) / 1000).coerceAtLeast(0))
        }
    }
}
