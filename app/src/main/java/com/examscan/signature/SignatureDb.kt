package com.examscan.signature

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class RiskLevel { HIGH, MEDIUM, LOW, INFO }

data class Category(val id: String, val label: String, val risk: RiskLevel)

class BleMatch(
    val companyId: Int?,
    val mfgDataPrefix: ByteArray?,   // company ID 뒤에 오는 바이트의 접두사
    val serviceUuid: String?,        // 128-bit 소문자로 정규화된 값
    val nameRegex: Regex?,
)

class WifiMatch(
    val ssidRegex: Regex?,
    val locallyAdministered: Boolean?,
    val hiddenSsid: Boolean?,
)

data class Rule<M>(
    val id: String,
    val categoryId: String,
    val label: String,
    val riskOverride: RiskLevel?,
    val priority: Int,
    val match: M,
    val requiresVerification: Boolean = false,
)

data class SignatureDb(
    val version: Int,
    val categories: Map<String, Category>,
    val bleRules: List<Rule<BleMatch>>,   // priority 내림차순 정렬됨
    val wifiRules: List<Rule<WifiMatch>>, // priority 내림차순 정렬됨
)

object SignatureLoader {

    fun fromAssets(context: Context, fileName: String = "signatures.json"): SignatureDb =
        context.assets.open(fileName).bufferedReader().use {
            parse(it.readText(), context.resources.configuration.locales[0].language == "ko")
        }

    fun parse(json: String, korean: Boolean = false): SignatureDb {
        val root = JSONObject(json)

        val catObj = root.getJSONObject("categories")
        val categories = catObj.keys().asSequence().associateWith { id ->
            val c = catObj.getJSONObject(id)
            Category(id, c.label(korean), RiskLevel.valueOf(c.getString("risk")))
        }

        val bleRules = root.optJSONArray("ble_rules").objects().map { r ->
            val m = r.getJSONObject("match")
            r.toRule(categories, korean) {
                BleMatch(
                    companyId = m.stringOrNull("company_id")?.let(::parseHexInt),
                    mfgDataPrefix = m.stringOrNull("mfg_data_prefix")?.let(::hexToBytes),
                    serviceUuid = m.stringOrNull("service_uuid")?.let(::normalizeUuid),
                    nameRegex = m.stringOrNull("name_regex")?.let(::Regex),
                )
            }
        }

        val wifiRules = root.optJSONArray("wifi_rules").objects().map { r ->
            val m = r.getJSONObject("match")
            r.toRule(categories, korean) {
                WifiMatch(
                    ssidRegex = m.stringOrNull("ssid_regex")?.let(::Regex),
                    locallyAdministered = m.boolOrNull("locally_administered"),
                    hiddenSsid = m.boolOrNull("hidden_ssid"),
                )
            }
        }

        return SignatureDb(
            version = root.optInt("version", 0),
            categories = categories,
            bleRules = bleRules.sortedByDescending { it.priority },
            wifiRules = wifiRules.sortedByDescending { it.priority },
        )
    }

    private fun <M> JSONObject.toRule(categories: Map<String, Category>, korean: Boolean, match: () -> M): Rule<M> {
        val id = getString("id")
        val categoryId = getString("category")
        require(categoryId in categories) { "rule '$id': 알 수 없는 category '$categoryId'" }
        return Rule(
            id = id,
            categoryId = categoryId,
            label = label(korean),
            riskOverride = stringOrNull("risk")?.let(RiskLevel::valueOf),
            priority = optInt("priority", 0),
            match = match(),
            requiresVerification = optBoolean("verify", false),
        )
    }

    private fun JSONObject.label(korean: Boolean): String =
        if (korean) getString("label") else optString("label_en", getString("label"))

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.boolOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    fun parseHexInt(s: String): Int = s.removePrefix("0x").removePrefix("0X").toInt(16)

    fun hexToBytes(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0) { "hex 길이가 홀수: $s" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** "FD5F" 같은 16-bit, 32-bit UUID를 표준 128-bit 문자열로 변환 */
    fun normalizeUuid(s: String): String {
        val c = s.trim().lowercase(Locale.ROOT)
        return when (c.length) {
            4 -> "0000$c-0000-1000-8000-00805f9b34fb"
            8 -> "$c-0000-1000-8000-00805f9b34fb"
            else -> c
        }
    }
}
