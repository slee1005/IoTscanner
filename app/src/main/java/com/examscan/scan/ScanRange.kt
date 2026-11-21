package com.examscan.scan

/** A signal-strength scope, not a physical radius or a change to radio transmit power. */
object ScanRange {
    const val MIN_RSSI = -100
    const val MAX_RSSI = -40
    const val NEARBY = -60
    const val BALANCED = -75
    const val WIDE = -90

    fun normalize(minimumRssi: Int?): Int? = minimumRssi?.coerceIn(MIN_RSSI, MAX_RSSI)
    fun includes(smoothedRssi: Double, minimumRssi: Int?): Boolean =
        minimumRssi == null || smoothedRssi >= minimumRssi
}
