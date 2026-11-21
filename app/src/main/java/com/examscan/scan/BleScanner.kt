package com.examscan.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android BLE 스캔 API로 주변 광고 패킷을 수신한다. 페어링하거나 연결하지 않는다.
 *
 * 주의:
 * - 필터 없는 스캔은 Android 8.1+에서 화면이 꺼지면 일시정지된다. 시험 중에는 화면을 켜두거나
 *   다음 단계에서 Foreground Service로 옮길 것.
 * - 30초 안에 startScan/stopScan을 5회 넘게 반복하면 OS가 스캔을 막는다. 스캔은 길게 한 번 유지.
 */
class BleScanner(context: Context) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    class ScanFailedException(val code: Int) : Exception("BLE scan failed: code=$code")

    @SuppressLint("MissingPermission") // 호출 전에 ScanPermissions.allGranted()로 확인할 것
    fun scan(): Flow<BleSignal> = callbackFlow {
        val a = adapter
        if (a == null || !a.isEnabled) {
            close(IllegalStateException("블루투스가 꺼져 있거나 지원되지 않습니다"))
            return@callbackFlow
        }
        val scanner = a.bluetoothLeScanner ?: run {
            close(IllegalStateException("BLE 스캐너를 사용할 수 없습니다"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .apply {
                // 확장 광고(BT 5) 지원 기기면 legacy 외 패킷도 수신
                if (a.isLeExtendedAdvertisingSupported) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toSignal())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toSignal()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }
        }

        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun ScanResult.toSignal(): BleSignal {
        val record = scanRecord

        val mfg = HashMap<Int, ByteArray>()
        record?.manufacturerSpecificData?.let { sa ->
            for (i in 0 until sa.size()) mfg[sa.keyAt(i)] = sa.valueAt(i)
        }

        val uuids = record?.serviceUuids
            ?.map { it.uuid.toString().lowercase() }
            ?.toSet()
            ?: emptySet()

        return BleSignal(
            address = device.address,
            // device.name은 BLUETOOTH_CONNECT 권한이 필요하므로 광고 패킷의 이름을 사용
            name = record?.deviceName?.takeIf { it.isNotBlank() },
            rssi = rssi,
            txPower = txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT },
            manufacturerData = mfg,
            serviceUuids = uuids,
            timestampMs = System.currentTimeMillis(),
        )
    }
}
