package com.examscan.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * 주변 Wi-Fi AP / 핫스팟 / Wi-Fi Direct 그룹을 주기적으로 스캔한다.
 *
 * 주의:
 * - Android 9+ 포그라운드 앱은 2분에 4회로 스캔이 제한된다 → 기본 간격 30초.
 *   테스트할 때는 개발자 옵션의 "Wi-Fi 스캔 제한(throttling)"을 끄면 더 자주 스캔된다.
 * - 기기의 위치 서비스(GPS 토글)가 꺼져 있으면 결과가 비어서 온다.
 * - AP(공유기, 핫스팟)만 보인다. AP에 접속한 클라이언트 기기는 휴대폰으로는 탐지 불가 (모니터 모드 필요).
 */
class WifiScanner(context: Context, private val intervalMs: Long = 30_000) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<WifiSignal>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // EXTRA_RESULTS_UPDATED == false면 스캔이 제한되어 캐시 결과가 온 것.
                // 각 결과의 자체 timestamp로 실제 측정 시각을 계산하므로 그대로 사용해도 된다.
                trySend(currentResults())
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        trySend(currentResults()) // 캐시된 결과로 즉시 화면 채우기

        val loop = launch {
            while (isActive) {
                @Suppress("DEPRECATION") // 대체 API 없음. 써드파티 앱의 유일한 스캔 트리거
                wifi.startScan()
                delay(intervalMs)
            }
        }

        awaitClose {
            loop.cancel()
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentResults(): List<WifiSignal> {
        val nowWall = System.currentTimeMillis()
        val nowBootUs = SystemClock.elapsedRealtime() * 1000
        return wifi.scanResults.map { it.toSignal(nowWall, nowBootUs) }
    }

    private fun ScanResult.toSignal(nowWall: Long, nowBootUs: Long): WifiSignal {
        val rawSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()
        } else {
            @Suppress("DEPRECATION") SSID
        }
        val ssid = rawSsid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        // ScanResult.timestamp = 부팅 후 마이크로초 → 실제 측정 시각(ms)으로 환산
        val measuredAt = nowWall - (nowBootUs - timestamp) / 1000

        return WifiSignal(
            bssid = BSSID.uppercase(),
            ssid = ssid,
            rssi = level,
            frequencyMhz = frequency,
            timestampMs = measuredAt,
        )
    }
}
