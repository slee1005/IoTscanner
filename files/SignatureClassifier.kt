package com.examscan.signature

import com.examscan.scan.BleSignal
import com.examscan.scan.Classification
import com.examscan.scan.WifiSignal

class SignatureClassifier(private val db: SignatureDb) {

    fun classify(s: BleSignal): Classification =
        db.bleRules.firstOrNull { matches(it.match, s) }?.toClassification()
            ?: fallback("unknown")

    fun classify(s: WifiSignal): Classification =
        db.wifiRules.firstOrNull { matches(it.match, s) }?.toClassification()
            ?: fallback("access_point")

    private fun matches(m: BleMatch, s: BleSignal): Boolean {
        // 조건이 하나도 없는 규칙은 전부를 매칭해버리므로 무시
        if (m.companyId == null && m.serviceUuid == null && m.nameRegex == null) return false

        if (m.companyId != null) {
            val data = s.manufacturerData[m.companyId] ?: return false
            if (m.mfgDataPrefix != null && !data.startsWith(m.mfgDataPrefix)) return false
        }
        if (m.serviceUuid != null && m.serviceUuid !in s.serviceUuids) return false
        if (m.nameRegex != null) {
            val name = s.name ?: return false
            if (!m.nameRegex.containsMatchIn(name)) return false
        }
        return true
    }

    private fun matches(m: WifiMatch, s: WifiSignal): Boolean {
        if (m.ssidRegex == null && m.locallyAdministered == null && m.hiddenSsid == null) return false

        if (m.ssidRegex != null) {
            val ssid = s.ssid ?: return false
            if (!m.ssidRegex.containsMatchIn(ssid)) return false
        }
        if (m.locallyAdministered != null && isLocallyAdministered(s.bssid) != m.locallyAdministered) return false
        if (m.hiddenSsid != null && (s.ssid == null) != m.hiddenSsid) return false
        return true
    }

    private fun Rule<*>.toClassification(): Classification {
        val cat = db.categories.getValue(categoryId)
        return Classification(id, categoryId, label, riskOverride ?: cat.risk)
    }

    private fun fallback(categoryId: String): Classification {
        val cat = db.categories[categoryId] ?: Category(categoryId, "미확인", RiskLevel.LOW)
        return Classification(null, cat.id, cat.label, cat.risk)
    }

    companion object {
        /** MAC 첫 옥텟의 두 번째 비트(0x02) = 로컬 관리 주소(랜덤 MAC). 휴대폰 핫스팟에서 흔함 */
        fun isLocallyAdministered(mac: String): Boolean =
            mac.substringBefore(':').toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
