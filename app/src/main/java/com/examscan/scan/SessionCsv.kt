package com.examscan.scan

import java.time.Instant

object SessionCsv {
    /** Quote every field and neutralize spreadsheet formulas in untrusted advertised names. */
    fun encode(devices: List<DetectedDevice>, demo: Boolean, scanMinimumRssi: Int? = null): String {
        fun field(value: Any?): String {
            var s = value?.toString().orEmpty()
            if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) s = "'$s"
            return "\"${s.replace("\"", "\"\"")}\""
        }
        val header = "mode,source,address,name,category,signature,priority,needs_verification,baseline,rssi_dbm,smoothed_rssi_dbm,observations,first_seen_utc,last_seen_utc,scan_minimum_rssi_dbm,within_selected_range"
        return header + "\r\n" + devices.joinToString("\r\n") { d ->
            listOf(if (demo) "DEMO" else "LIVE", d.source, d.key.substringAfter(':'), d.displayName,
                d.classification.categoryId, d.classification.ruleId, d.classification.risk,
                d.classification.requiresVerification, d.isBaseline, d.rssi, "%.1f".format(java.util.Locale.ROOT, d.smoothedRssi),
                d.seenCount, Instant.ofEpochMilli(d.firstSeenMs), Instant.ofEpochMilli(d.lastSeenMs),
                scanMinimumRssi, ScanRange.includes(d.smoothedRssi, scanMinimumRssi))
                .mapIndexed { i, v -> if (i in setOf(9, 10, 11, 14)) "\"${v ?: ""}\"" else field(v) }.joinToString(",")
        } + "\r\n"
    }
}
