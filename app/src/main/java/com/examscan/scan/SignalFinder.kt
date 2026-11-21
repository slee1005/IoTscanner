package com.examscan.scan

enum class FinderStatus { COLLECTING, STRONGER, WEAKER, STEADY, LOST, PAUSED, UNAVAILABLE }

data class FinderPoint(val timestampMs: Long, val rssi: Double)

data class FinderReading(
    val status: FinderStatus,
    val currentRssi: Double?,
    val bestRssi: Double?,
    val changeDb: Double?,
    val lastSeenMs: Long?,
    val ageMs: Long?,
    val points: List<FinderPoint>,
)

/** Compares received strength over time, without inferring physical movement, bearing, or distance. */
class SignalFinder(private val source: SignalSource) {
    private val samples = ArrayDeque<FinderPoint>()
    private var lastSeenMs: Long? = null
    private var currentRssi: Double? = null
    private var bestRssi: Double? = null
    private val staleAfterMs = if (source == SignalSource.BLE) 15_000L else 90_000L

    fun update(device: DetectedDevice?, nowMs: Long, scanning: Boolean, available: Boolean = true): FinderReading {
        // Use the packet's timestamp, never the UI refresh time, to reject cached observations.
        if (device != null && (lastSeenMs == null || scanning) &&
            device.lastSeenMs > (lastSeenMs ?: Long.MIN_VALUE) &&
            device.lastSeenMs <= nowMs + 1_000 && device.smoothedRssi.isFinite()) {
            if (lastSeenMs?.let { device.lastSeenMs - it > staleAfterMs } == true) samples.clear()
            lastSeenMs = device.lastSeenMs
            currentRssi = device.smoothedRssi
            if (nowMs - device.lastSeenMs <= staleAfterMs) {
                bestRssi = maxOf(bestRssi ?: device.smoothedRssi, device.smoothedRssi)
                // Downsample to one point per second so busy advertisers cannot dominate the trend.
                if (samples.lastOrNull()?.timestampMs?.div(1_000) == device.lastSeenMs / 1_000) samples.removeLast()
                samples.addLast(FinderPoint(device.lastSeenMs, device.smoothedRssi))
                while (samples.size > 90 || samples.first().timestampMs < device.lastSeenMs - 300_000) samples.removeFirst()
            }
        }
        val age = lastSeenMs?.let { (nowMs - it).coerceAtLeast(0) }
        val change = comparison()
        val status = when {
            !scanning -> FinderStatus.PAUSED
            !available -> FinderStatus.UNAVAILABLE
            age != null && age > staleAfterMs -> FinderStatus.LOST
            change == null -> FinderStatus.COLLECTING
            change >= 3.0 -> FinderStatus.STRONGER
            change <= -3.0 -> FinderStatus.WEAKER
            else -> FinderStatus.STEADY
        }
        return FinderReading(status, currentRssi, bestRssi,
            if (status in setOf(FinderStatus.STRONGER, FinderStatus.WEAKER, FinderStatus.STEADY)) change else null,
            lastSeenMs, age, samples.toList())
    }

    private fun comparison(): Double? {
        if (samples.size < 2) return null
        val end = samples.last().timestampMs
        if (source == SignalSource.WIFI) {
            val previous = samples.elementAt(samples.size - 2)
            if (end - previous.timestampMs < 1_000) return null
            return samples.last().rssi - previous.rssi
        }
        if (end - samples.first().timestampMs < 3_000) return null
        val recent = samples.filter { it.timestampMs > end - 3_000 }
        val reference = samples.filter { it.timestampMs in (end - 10_000)..(end - 3_000) }
        if (recent.size < 2 || reference.size < 2) return null
        return recent.map { it.rssi }.average() - reference.map { it.rssi }.average()
    }
}

data class FindingSession(val device: DetectedDevice, val reading: FinderReading)
