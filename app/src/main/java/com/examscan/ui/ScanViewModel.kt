package com.examscan.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.location.LocationManagerCompat
import com.examscan.scan.*
import com.examscan.signature.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.sin

/** Retained by the Activity across configuration changes. Radio work is foreground-only. */
class ScanViewModel(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("scanner", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = DetectionRepository(SignatureClassifier(SignatureLoader.fromAssets(app)))
    val devices = repo.visible
    val alerts = repo.alerts
    val alertHistory = repo.alertHistory
    val isRecordingBaseline = repo.isRecordingBaseline
    val scanning = MutableStateFlow(false)
    val demo = MutableStateFlow(false)
    val bleStatus = MutableStateFlow(ChannelStatus.IDLE)
    val wifiStatus = MutableStateFlow(ChannelStatus.IDLE)
    val permissionNeeded = MutableStateFlow(false)
    private val _scanRange = MutableStateFlow<Int?>(
        if (prefs.contains("scan_minimum_rssi")) ScanRange.normalize(prefs.getInt("scan_minimum_rssi", ScanRange.WIDE)) else null)
    val scanRange = _scanRange.asStateFlow()
    private val _finding = MutableStateFlow<FindingSession?>(null)
    val finding = _finding.asStateFlow()
    private var finder: SignalFinder? = null
    private var finderJob: Job? = null
    var threshold = prefs.getInt("threshold", -80).coerceIn(-100, -40)
        private set
    var sound = prefs.getBoolean("sound", false)
        private set
    var sessionStartedMs = System.currentTimeMillis()
        private set
    private var job: Job? = null
    init { repo.alertRssiThreshold = threshold; repo.setScanRange(_scanRange.value) }

    @SuppressLint("MissingPermission")
    fun start() {
        if (job?.isActive == true) return
        if (demo.value) { startDemo(reset = false); return }
        if (!ScanPermissions.allGranted(app)) { permissionNeeded.value = true; return }
        permissionNeeded.value = false
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        val location = app.getSystemService(LocationManager::class.java)
        val locationOn = location != null && LocationManagerCompat.isLocationEnabled(location)
        val wifi = app.getSystemService(WifiManager::class.java)
        val bleReady = adapter != null && adapter.isEnabled && locationOn
        val wifiReady = wifi != null && (wifi.isWifiEnabled || wifi.isScanAlwaysAvailable) && locationOn
        bleStatus.value = when { adapter == null -> ChannelStatus.UNAVAILABLE; !bleReady -> ChannelStatus.OFF; else -> ChannelStatus.WAITING }
        wifiStatus.value = when { wifi == null -> ChannelStatus.UNAVAILABLE; !wifiReady -> ChannelStatus.OFF; else -> ChannelStatus.WAITING }
        if (!bleReady && !wifiReady) return
        scanning.value = true
        startFinderTicker()
        job = scope.launch {
            supervisorScope {
                val radios = buildList {
                    if (bleReady) add(launch {
                        try {
                            bleStatus.value = ChannelStatus.SCANNING
                            BleScanner(app).scan().collect(repo::onBle)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { bleStatus.value = ChannelStatus.ERROR }
                    })
                    if (wifiReady) add(launch {
                        try { WifiScanner(app).scan { wifiStatus.value = it }.collect(repo::onWifi) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { wifiStatus.value = ChannelStatus.ERROR }
                    })
                }
                val publisher = launch { while (isActive) { repo.publish(); delay(750) } }
                radios.joinAll()
                publisher.cancel()
            }
            scanning.value = false
            repo.publish()
            refreshFinder()
            finderJob?.cancel(); finderJob = null
        }
    }

    fun stop() {
        job?.cancel(); job = null; scanning.value = false
        if (bleStatus.value in setOf(ChannelStatus.SCANNING, ChannelStatus.WAITING)) bleStatus.value = ChannelStatus.IDLE
        if (wifiStatus.value in setOf(ChannelStatus.SCANNING, ChannelStatus.WAITING, ChannelStatus.THROTTLED)) wifiStatus.value = ChannelStatus.IDLE
        if (isRecordingBaseline.value) repo.finishBaseline()
        repo.publish()
        refreshFinder()
        finderJob?.cancel(); finderJob = null
    }
    fun startDemo(reset: Boolean = true) {
        stop()
        if (reset) { stopFinding(); repo.resetSession(); sessionStartedMs = System.currentTimeMillis() }
        demo.value = true; permissionNeeded.value = false; scanning.value = true
        bleStatus.value = ChannelStatus.SCANNING; wifiStatus.value = ChannelStatus.SCANNING
        startFinderTicker()
        job = scope.launch {
            var tick = 0
            while (isActive) {
                val now = System.currentTimeMillis()
                val delta = (sin(tick / 2.0) * 4).toInt()
                fun ble(address: String, name: String?, rssi: Int, id: Int? = null, data: String = "", uuids: Set<String> = emptySet()) =
                    repo.onBle(BleSignal(address, name, rssi + delta, null,
                        if (id == null) emptyMap() else mapOf(id to SignatureLoader.hexToBytes(data)), uuids, now))
                ble("02:DE:00:00:00:01", "AirPods Pro", -48, 0x004c, "070119")
                ble("02:DE:00:00:00:02", "Garmin Venu 3", -66)
                ble("02:DE:00:00:00:03", "Unknown advertiser", -83)
                ble("02:DE:00:00:00:04", "Office beacon", -72, 0x004c, "0215001122")
                ble("02:DE:00:00:00:05", "Unverified service", -58, uuids = setOf(SignatureLoader.normalizeUuid("FD5F")))
                repo.onWifi(listOf(WifiSignal("00:DE:00:00:00:06", "Studio Network", -43 + delta, 5180, now),
                    WifiSignal("02:DE:00:00:00:07", "Pixel hotspot", -62 + delta, 2437, now)))
                repo.publish(); tick++; delay(2_000)
            }
        }
    }
    fun leaveDemo() { stop(); stopFinding(); demo.value = false; repo.resetSession(); sessionStartedMs = System.currentTimeMillis() }
    fun startBaseline() { if (scanning.value) repo.startBaseline() }
    fun finishBaseline() = repo.finishBaseline()
    fun whitelist(device: DetectedDevice) = repo.addToBaseline(device.key)
    fun removeBaseline(device: DetectedDevice) = repo.removeFromBaseline(device.key)
    fun setAlertThreshold(dbm: Int) { threshold = dbm.coerceIn(-100, -40); repo.alertRssiThreshold = threshold; prefs.edit().putInt("threshold", threshold).apply() }
    fun setScanRange(minimumRssi: Int?) {
        val normalized = ScanRange.normalize(minimumRssi)
        repo.setScanRange(normalized)
        _scanRange.value = normalized
        prefs.edit().apply {
            if (normalized == null) remove("scan_minimum_rssi") else putInt("scan_minimum_rssi", normalized)
        }.apply()
    }
    fun setSound(enabled: Boolean) { sound = enabled; prefs.edit().putBoolean("sound", enabled).apply() }
    fun resetSession() { stop(); stopFinding(); repo.resetSession(); sessionStartedMs = System.currentTimeMillis() }
    fun sessionLog() = repo.allDevices()
    fun findDevice(key: String) {
        val device = repo.device(key) ?: return
        finderJob?.cancel(); finderJob = null
        val tracker = SignalFinder(device.source)
        finder = tracker
        _finding.value = FindingSession(device, tracker.update(device, System.currentTimeMillis(), scanning.value))
        refreshFinder()
        startFinderTicker()
    }
    fun stopFinding() {
        finderJob?.cancel(); finderJob = null; finder = null; _finding.value = null
    }
    private fun refreshFinder() {
        val session = _finding.value ?: return
        val tracker = finder ?: return
        val device = repo.device(session.device.key) ?: run { stopFinding(); return }
        val channel = if (device.source == SignalSource.BLE) bleStatus.value else wifiStatus.value
        val available = channel !in setOf(ChannelStatus.OFF, ChannelStatus.UNAVAILABLE, ChannelStatus.ERROR)
        _finding.value = FindingSession(device, tracker.update(device, System.currentTimeMillis(), scanning.value, available))
    }
    private fun startFinderTicker() {
        if (_finding.value == null || !scanning.value || finderJob?.isActive == true) return
        finderJob = scope.launch { while (isActive) { refreshFinder(); delay(500) } }
    }
    fun close() { stop(); stopFinding(); scope.cancel() }
}
