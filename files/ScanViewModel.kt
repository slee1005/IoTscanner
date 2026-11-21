package com.examscan.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.examscan.scan.BleScanner
import com.examscan.scan.DetectedDevice
import com.examscan.scan.DetectionRepository
import com.examscan.scan.ScanPermissions
import com.examscan.scan.WifiScanner
import com.examscan.signature.SignatureClassifier
import com.examscan.signature.SignatureLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DetectionRepository(
        classifier = SignatureClassifier(SignatureLoader.fromAssets(app)),
    )
    private val ble = BleScanner(app)
    private val wifi = WifiScanner(app)

    val devices: StateFlow<List<DetectedDevice>> = repo.visible
    val alerts: SharedFlow<DetectedDevice> = repo.alerts
    val isRecordingBaseline: StateFlow<Boolean> = repo.isRecordingBaseline

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    fun start() {
        val ctx = getApplication<Application>()
        if (!ScanPermissions.allGranted(ctx)) {
            _error.value = "필요한 권한이 허용되지 않았습니다"
            return
        }
        ScanPermissions.missingSystemToggles(ctx).takeIf { it.isNotEmpty() }?.let {
            _error.value = "${it.joinToString(", ")}을(를) 켜주세요"
            return
        }
        if (scanJob?.isActive == true) return

        _error.value = null
        _scanning.value = true
        scanJob = viewModelScope.launch {
            launch {
                ble.scan()
                    .catch { _error.value = "BLE 스캔 오류: ${it.message}" }
                    .collect(repo::onBle)
            }
            launch {
                wifi.scan()
                    .catch { _error.value = "Wi-Fi 스캔 오류: ${it.message}" }
                    .collect(repo::onWifi)
            }
            while (isActive) {
                repo.publish()
                delay(500)
            }
        }.also { job -> job.invokeOnCompletion { _scanning.value = false } }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    fun startBaseline() = repo.startBaseline()
    fun finishBaseline() = repo.finishBaseline()
    fun whitelist(device: DetectedDevice) = repo.addToBaseline(device.key)
    fun setAlertThreshold(dbm: Int) { repo.alertRssiThreshold = dbm }
    fun resetSession() = repo.resetSession()
    fun sessionLog(): List<DetectedDevice> = repo.allDevices()
}
