package com.examscan.scan

import com.examscan.signature.RiskLevel
import com.examscan.signature.SignatureClassifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.Locale

/** Session memory is local only. The injected clock makes cache handling deterministic. */
class DetectionRepository(
    private val classifier: SignatureClassifier,
    var alertRssiThreshold: Int = -80,
    private val staleAfterMs: Long = 60_000,
    private val alertLevels: Set<RiskLevel> = setOf(RiskLevel.HIGH, RiskLevel.MEDIUM),
    private val emaAlpha: Double = 0.3,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val devices = LinkedHashMap<String, DetectedDevice>()
    private val blePackets = HashMap<String, BleSignal>()
    private val baselineKeys = HashSet<String>()
    private var recording = false
    private var scanMinimumRssi: Int? = null
    private val _visible = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val visible = _visible.asStateFlow()
    private val _alerts = MutableSharedFlow<DetectedDevice>(extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val alerts = _alerts.asSharedFlow()
    private val _alertHistory = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val alertHistory = _alertHistory.asStateFlow()
    private val _isRecordingBaseline = MutableStateFlow(false)
    val isRecordingBaseline = _isRecordingBaseline.asStateFlow()

    fun onBle(s: BleSignal) = synchronized(lock) {
        if (s.rssi !in -127..20 || s.timestampMs > clock() + 1_000 || clock() - s.timestampMs > staleAfterMs) return@synchronized
        val address = s.address.uppercase(Locale.ROOT)
        val key = "ble:$address"
        val old = blePackets[key]
        if (old != null && s.timestampMs <= old.timestampMs) return@synchronized
        // Advertising fields arrive in separate packets; nameless packets must not erase evidence.
        val merged = BleSignal(address, s.name ?: old?.name, s.rssi, s.txPower ?: old?.txPower,
            old?.manufacturerData.orEmpty() + s.manufacturerData,
            old?.serviceUuids.orEmpty() + s.serviceUuids, s.timestampMs)
        blePackets[key] = merged
        upsert(key, SignalSource.BLE, merged.name ?: address, merged.rssi, merged.timestampMs,
            classifier.classify(merged), merged.serviceUuids, merged.manufacturerData.keys)
    }

    fun onWifi(signals: List<WifiSignal>) = synchronized(lock) {
        signals.forEach { s ->
            upsert("wifi:${s.bssid.uppercase(Locale.ROOT)}", SignalSource.WIFI,
                s.ssid ?: s.bssid, s.rssi, s.timestampMs, classifier.classify(s), frequency = s.frequencyMhz)
        }
    }

    private fun upsert(key: String, source: SignalSource, name: String, rssi: Int, ts: Long,
        classification: Classification, services: Set<String> = emptySet(),
        manufacturers: Set<Int> = emptySet(), frequency: Int? = null) {
        val now = clock()
        if (rssi !in -127..20 || ts > now + 1_000 || now - ts > staleAfterMs) return
        val prev = devices[key]
        if (prev != null && ts <= prev.lastSeenMs) return
        var d = DetectedDevice(key, source, name, classification, rssi,
            prev?.let { emaAlpha * rssi + (1 - emaAlpha) * it.smoothedRssi } ?: rssi.toDouble(),
            prev?.firstSeenMs ?: ts, ts, (prev?.seenCount ?: 0) + 1,
            key in baselineKeys, prev?.alerted ?: false, services, manufacturers, frequency,
            (prev?.rssiHistory.orEmpty() + rssi).takeLast(30))
        if (recording && inRange(d)) {
            baselineKeys += key
            d = d.copy(isBaseline = true)
        }
        if (inRange(d) && !recording && !d.isBaseline && !d.alerted && !classification.requiresVerification &&
            classification.risk in alertLevels && d.smoothedRssi >= alertRssiThreshold) {
            d = d.copy(alerted = true)
            _alertHistory.value = (listOf(d) + _alertHistory.value).take(200)
            _alerts.tryEmit(d)
        }
        devices[key] = d
    }

    fun startBaseline() = synchronized(lock) {
        recording = true
        _isRecordingBaseline.value = true
        val now = clock()
        devices.replaceAll { key, d ->
            if (now - d.lastSeenMs <= staleAfterMs && inRange(d)) {
                baselineKeys += key
                d.copy(isBaseline = true)
            } else d
        }
        publish()
    }
    fun finishBaseline() = synchronized(lock) {
        recording = false
        _isRecordingBaseline.value = false
        publish()
    }
    fun addToBaseline(key: String) = synchronized(lock) {
        baselineKeys += key
        devices[key]?.let { devices[key] = it.copy(isBaseline = true) }
        publish()
    }
    fun removeFromBaseline(key: String) = synchronized(lock) {
        baselineKeys -= key
        devices[key]?.let { devices[key] = it.copy(isBaseline = false, alerted = false) }
        publish()
    }
    fun resetSession() = synchronized(lock) {
        devices.clear(); blePackets.clear(); baselineKeys.clear(); recording = false
        _isRecordingBaseline.value = false; _visible.value = emptyList(); _alertHistory.value = emptyList()
    }
    /** Re-scope existing observations immediately without restarting Android's radio scanners. */
    fun setScanRange(minimumRssi: Int?) = synchronized(lock) {
        scanMinimumRssi = ScanRange.normalize(minimumRssi)
        publish()
    }
    private fun inRange(device: DetectedDevice) = ScanRange.includes(device.smoothedRssi, scanMinimumRssi)
    fun publish() = synchronized(lock) {
        val now = clock()
        _visible.value = devices.values.filter { now - it.lastSeenMs <= staleAfterMs && inRange(it) }
            .sortedWith(compareBy<DetectedDevice>({ it.isBaseline },
                { it.classification.risk.ordinal }, { -it.smoothedRssi }, { it.key }))
    }
    fun allDevices(): List<DetectedDevice> = synchronized(lock) { devices.values.toList() }
    fun device(key: String): DetectedDevice? = synchronized(lock) { devices[key] }
}
