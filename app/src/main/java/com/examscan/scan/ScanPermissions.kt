package com.examscan.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object ScanPermissions {
    fun required(): Array<String> = buildList {
        // Coarse and fine must be requested together on Android 12+.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            // Checking the adapter state needs CONNECT; the app never connects to devices.
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()
    fun allGranted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
