package com.examscan.scan

import com.examscan.signature.RiskLevel
import com.examscan.signature.SignatureClassifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE/Wi-Fi 신호를 기기 단위로 합치고, 베이스라인 관리와 신규 기기 알림을 담당한다.
 *
 * BLE 패킷은 초당 수백 개 들어오므로 UI 갱신은 publish()를 주기적으로 호출해서 처리한다.
 */
class DetectionRepository(
    private val classifier: SignatureClassifier,
    var alertRssiThreshold: Int = -80,          // 이보다 약한 신호는 옆 교실로 보고 알림 안 함
    private val staleAfterMs: Long = 60_000,    // 마지막 수신 후 이 시간이 지나면 목록에서 숨김
    private val alertLevels: Set<RiskLevel> = setOf(RiskLevel.HIGH, RiskLevel.MEDIUM),
    private val emaAlpha: Double = 0.3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val devices = LinkedHashMap<String, DetectedDevice>()
    private val baselineKeys = HashSet<String>()
    private var baselineRecording = false

    private val _visible = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val visible: StateFlow<List<DetectedDevice>> = _visible.asStateFlow()

    private val _alerts = MutableSharedFlow<DetectedDevice>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alerts: SharedFlow<DetectedDevice> = _alerts.asSharedFlow()

    private val _isRecordingBaseline = MutableStateFlow(false)
    val isRecordingBaseline: StateFlow<Boolean> = _isRecordingBaseline.asStateFlow()

    // ---------- 입력 ----------

    fun onBle(s: BleSignal) = upsert(
        key = "ble:${s.address}",
        source = SignalSource.BLE,
        name = s.name ?: s.address,
        rssi = s.rssi,
        ts = s.timestampMs,
    ) { classifier.classify(s) }

    fun onWifi(list: List<WifiSignal>) = list.forEach { s ->
        upsert(
            key = "wifi:${s.bssid}",
            source = SignalSource.WIFI,
            name = s.ssid ?: "(숨김 SSID) ${s.bssid}",
            rssi = s.rssi,
            ts = s.timestampMs,
        ) { classifier.classify(s) }
    }

    private fun upsert(
        key: String,
        source: SignalSource,
        name: String,
        rssi: Int,
        ts: Long,
        classify: () -> Classification,
    ) {
        var toAlert: DetectedDevice? = null

        synchronized(lock) {
            val prev = devices[key]
            if (prev != null && ts <= prev.lastSeenMs) return // 캐시된 오래된 Wi-Fi 결과 무시

            var device = if (prev == null) {
                if (baselineRecording) baselineKeys += key
                DetectedDevice(
                    key = key,
                    source = source,
                    displayName = name,
                    classification = classify(),
                    rssi = rssi,
                    smoothedRssi = rssi.toDouble(),
                    firstSeenMs = ts,
                    lastSeenMs = ts,
                    seenCount = 1,
                    isBaseline = key in baselineKeys,
                    alerted = false,
                )
            } else {
                prev.copy(
                    displayName = name,
                    // 이름이 나중에 scan response로 도착하는 경우가 있어 이름이 바뀌면 재분류
                    classification = if (prev.displayName != name) classify() else prev.classification,
                    rssi = rssi,
                    smoothedRssi = emaAlpha * rssi + (1 - emaAlpha) * prev.smoothedRssi,
                    lastSeenMs = ts,
                    seenCount = prev.seenCount + 1,
                )
            }

            if (shouldAlert(device)) {
                device = device.copy(alerted = true)
                toAlert = device
            }
            devices[key] = device
        }

        toAlert?.let { _alerts.tryEmit(it) }
    }

    private fun shouldAlert(d: DetectedDevice): Boolean =
        !baselineRecording &&
            !d.isBaseline &&
            !d.alerted &&
            d.classification.risk in alertLevels &&
            d.smoothedRssi >= alertRssiThreshold

    // ---------- 베이스라인 ----------

    /** 수험생 입실 전 빈 시험장에서 호출. 지금 보이는 기기와 녹화 중 새로 보이는 기기를 모두 베이스라인에 등록 */
    fun startBaseline() = synchronized(lock) {
        baselineRecording = true
        _isRecordingBaseline.value = true
        devices.replaceAll { key, d ->
            baselineKeys += key
            d.copy(isBaseline = true)
        }
    }

    fun finishBaseline() = synchronized(lock) {
        baselineRecording = false
        _isRecordingBaseline.value = false
    }

    /** 감독관 기기 등을 수동으로 화이트리스트에 추가 */
    fun addToBaseline(key: String) = synchronized(lock) {
        baselineKeys += key
        devices[key]?.let { devices[key] = it.copy(isBaseline = true) }
    }

    fun resetSession() = synchronized(lock) {
        devices.clear()
        baselineKeys.clear()
        baselineRecording = false
        _isRecordingBaseline.value = false
        _visible.value = emptyList()
    }

    // ---------- 출력 ----------

    /** UI 갱신용. ViewModel에서 0.5~1초 간격으로 호출 */
    fun publish() {
        val now = clock()
        val snapshot = synchronized(lock) {
            devices.values.filter { now - it.lastSeenMs <= staleAfterMs }
        }
        _visible.value = snapshot.sortedWith(
            compareBy<DetectedDevice>(
                { it.isBaseline },                  // 베이스라인 아닌 기기 먼저
                { it.classification.risk.ordinal }, // HIGH → INFO
                { -it.smoothedRssi },               // 가까운 기기 먼저
            )
        )
    }

    /** 세션 리포트용 전체 기록 (stale 포함) */
    fun allDevices(): List<DetectedDevice> = synchronized(lock) { devices.values.toList() }
}
