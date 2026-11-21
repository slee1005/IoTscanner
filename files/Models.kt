package com.examscan.scan

import com.examscan.signature.RiskLevel

enum class SignalSource { BLE, WIFI }

/** BLE 광고 패킷 하나에서 뽑아낸 원시 정보 */
class BleSignal(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val manufacturerData: Map<Int, ByteArray>, // key = company ID
    val serviceUuids: Set<String>,             // 128-bit 소문자
    val timestampMs: Long,
)

class WifiSignal(
    val bssid: String,
    val ssid: String?,       // 숨김 SSID면 null
    val rssi: Int,
    val frequencyMhz: Int,
    val timestampMs: Long,
)

data class Classification(
    val ruleId: String?,     // null = 매칭된 규칙 없음 (fallback)
    val categoryId: String,
    val label: String,
    val risk: RiskLevel,
)

data class DetectedDevice(
    val key: String,          // "ble:AA:BB:.." 또는 "wifi:AA:BB:.."
    val source: SignalSource,
    val displayName: String,
    val classification: Classification,
    val rssi: Int,            // 마지막 측정값
    val smoothedRssi: Double, // EMA로 평활화한 값 (위치 추적 게이지용)
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val seenCount: Int,
    val isBaseline: Boolean,  // 시험 시작 전 베이스라인에 포함된 기기
    val alerted: Boolean,     // 이미 알림을 보냈는지 여부
)
