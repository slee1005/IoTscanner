package com.examscan.scan

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object ScanPermissions {

    /** 런타임에 요청해야 하는 권한 목록 (ActivityResultContracts.RequestMultiplePermissions에 전달) */
    fun required(): Array<String> = buildList {
        // BLUETOOTH_SCAN에 neverForLocation을 쓰면 일부 비콘 패킷이 필터링되므로 위치 권한을 함께 사용
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    fun allGranted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 권한 외에 스캔 전에 사용자에게 켜달라고 안내해야 하는 항목 */
    fun missingSystemToggles(context: Context): List<String> = buildList {
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) add("블루투스")
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !LocationManagerCompat.isLocationEnabled(lm)) add("위치 서비스")
    }
}
