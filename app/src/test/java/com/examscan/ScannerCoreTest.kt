package com.examscan

import com.examscan.scan.*
import com.examscan.signature.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScannerCoreTest {
    private val db get() = SignatureLoader.parse(File("src/main/assets/signatures.json").readText())
    private val classifier get() = SignatureClassifier(db)
    private var now = 100_000L
    private fun repository() = DetectionRepository(classifier, clock={now})
    private fun ble(name:String?="AirPods Pro",rssi:Int=-55,ts:Long=now,address:String="AA:BB:CC:DD:EE:01",
        manufacturer:Map<Int,ByteArray> = emptyMap(), services:Set<String> = emptySet()) =
        BleSignal(address,name,rssi,null,manufacturer,services,ts)
    private fun wifi(ssid:String?="Studio",bssid:String="00:11:22:33:44:55",ts:Long=now) = WifiSignal(bssid,ssid,-50,5180,ts)

    @Test fun uuidNormalizationAndHexParsing() {
        assertEquals("0000fd5f-0000-1000-8000-00805f9b34fb",SignatureLoader.normalizeUuid(" FD5F "))
        assertEquals("12345678-0000-1000-8000-00805f9b34fb",SignatureLoader.normalizeUuid("12345678"))
        assertArrayEquals(byteArrayOf(2,21,-1),SignatureLoader.hexToBytes("02 15 FF"))
        assertEquals(76,SignatureLoader.parseHexInt("0x004C"))
    }
    @Test fun specificPacketWinsOverGenericManufacturer() {
        val result=classifier.classify(ble(name=null,manufacturer=mapOf(76 to byteArrayOf(2,21,0))))
        assertEquals("apple_ibeacon",result.ruleId); assertEquals(RiskLevel.LOW,result.risk)
    }
    @Test fun manufacturerPrefixRequiresAllConditions() {
        val result=classifier.classify(ble(name=null,manufacturer=mapOf(117 to byteArrayOf(7,0))))
        assertEquals("samsung_generic",result.ruleId); assertEquals(RiskLevel.LOW,result.risk)
    }
    @Test fun verificationMetadataIsPreserved() {
        val result=classifier.classify(ble(name=null,services=setOf(SignatureLoader.normalizeUuid("FD5F"))))
        assertTrue(result.requiresVerification); assertEquals("meta_glasses_service",result.ruleId)
    }
    @Test fun emptyMatchRuleNeverClassifiesEverything() {
        val raw="""{"version":1,"categories":{"unknown":{"label":"Unknown","risk":"LOW"}},"ble_rules":[{"id":"empty","category":"unknown","label":"Empty","match":{}}]}"""
        assertNull(SignatureClassifier(SignatureLoader.parse(raw)).classify(ble(name=null)).ruleId)
    }
    @Test fun wifiNameAndLocalAddressAreNotConclusiveIdentity() {
        assertEquals("android_hotspot",classifier.classify(wifi("Pixel hotspot")).ruleId)
        val hidden=classifier.classify(wifi(null,"02:11:22:33:44:55"))
        assertTrue(hidden.requiresVerification); assertEquals(RiskLevel.LOW,hidden.risk)
        assertEquals("access_point",classifier.classify(wifi()).categoryId)
    }
    @Test fun duplicateWifiCacheDoesNotIncreaseObservationCount() {
        val repo=repository(); repo.onWifi(listOf(wifi())); repo.onWifi(listOf(wifi()))
        assertEquals(1,repo.allDevices().single().seenCount)
    }
    @Test fun olderWifiDoesNotReplaceFreshResult() {
        val repo=repository(); repo.onWifi(listOf(wifi("Studio"))); repo.onWifi(listOf(wifi("Old",ts=now-1)))
        assertEquals("Studio",repo.allDevices().single().displayName)
    }
    @Test fun baselineIncludesExistingAndNewDevicesAndSuppressesAlerts() {
        val repo=repository(); repo.onBle(ble(name="Beacon")); repo.startBaseline()
        repo.onBle(ble(address="AA:BB:CC:DD:EE:02")); repo.finishBaseline(); repo.publish()
        assertTrue(repo.visible.value.all { it.isBaseline }); assertTrue(repo.alertHistory.value.isEmpty())
    }
    @Test fun smoothSignalTriggersExactlyOneAlertWhenThresholdReached() {
        val repo=repository(); repo.onBle(ble(rssi=-90)); assertTrue(repo.alertHistory.value.isEmpty())
        now++; repo.onBle(ble(rssi=-40)); now++; repo.onBle(ble(rssi=-40))
        assertEquals(1,repo.alertHistory.value.size)
        assertEquals(-64.5,repo.allDevices().single().smoothedRssi,0.001)
    }
    @Test fun unverifiedHighPriorityDoesNotAutomaticallyAlert() {
        val repo=repository(); repo.onBle(ble(name=null,services=setOf(SignatureLoader.normalizeUuid("FD5F"))))
        assertTrue(repo.alertHistory.value.isEmpty()); assertTrue(repo.allDevices().single().classification.requiresVerification)
    }
    @Test fun classificationUpdatesWhenPacketEvidenceArrivesWithoutNameChange() {
        val repo=repository(); repo.onBle(ble(name=null)); now++
        repo.onBle(ble(name=null,manufacturer=mapOf(76 to byteArrayOf(7,1))))
        assertEquals("apple_proximity_pairing",repo.allDevices().single().classification.ruleId)
    }
    @Test fun separateAdvertisementPacketsRetainNameAndManufacturerEvidence() {
        val repo=repository(); repo.onBle(ble(manufacturer=mapOf(76 to byteArrayOf(7,1)))); now++
        repo.onBle(ble(name=null))
        assertEquals("AirPods Pro",repo.allDevices().single().displayName)
        assertEquals("apple_proximity_pairing",repo.allDevices().single().classification.ruleId)
    }
    @Test fun addressesAreCaseInsensitive() {
        val repo=repository(); repo.onBle(ble(address="aa:bb:cc:dd:ee:01")); now++
        repo.onBle(ble()); assertEquals(1,repo.allDevices().size)
    }
    @Test fun staleSignalsDisappearButRemainInSessionReport() {
        val repo=repository(); repo.onBle(ble()); now+=60_001; repo.publish()
        assertTrue(repo.visible.value.isEmpty()); assertEquals(1,repo.allDevices().size)
    }
    @Test fun staleCachedResultsDoNotCreateDevicesOrAlerts() {
        val repo=repository(); repo.onWifi(listOf(wifi("Pixel hotspot",ts=now-90_000)))
        assertTrue(repo.allDevices().isEmpty()); assertTrue(repo.alertHistory.value.isEmpty())
    }
    @Test fun baselineDoesNotCaptureExpiredDevices() {
        val repo=repository(); repo.onBle(ble(name="Beacon")); now+=60_001; repo.startBaseline(); repo.finishBaseline()
        now++; repo.onBle(ble()); assertFalse(repo.allDevices().single().isBaseline)
    }
    @Test fun removingBaselineRearmsAlertOnNextObservation() {
        val repo=repository(); repo.startBaseline(); repo.onBle(ble()); repo.finishBaseline()
        val d=repo.allDevices().single(); repo.removeFromBaseline(d.key); now++; repo.onBle(ble())
        assertEquals(1,repo.alertHistory.value.size)
    }
    @Test fun resetClearsDevicesBaselineAndAlertHistory() {
        val repo=repository(); repo.onBle(ble()); repo.startBaseline(); repo.resetSession()
        assertTrue(repo.allDevices().isEmpty()); assertTrue(repo.visible.value.isEmpty())
        assertTrue(repo.alertHistory.value.isEmpty()); assertFalse(repo.isRecordingBaseline.value)
    }
    @Test fun csvEscapesAdvertisedNamesAndLabelsDemo() {
        val repo=repository(); repo.onBle(ble(name="=SUM(1,2)\"\n"))
        val csv=SessionCsv.encode(repo.allDevices(),true)
        assertTrue(csv.contains("\"DEMO\"")); assertTrue(csv.contains("\"'=SUM(1,2)\"\"\n\""))
        assertTrue(csv.contains("\"-55\"")); assertTrue(csv.contains("1970-01-01T"))
    }
    @Test fun scanRangeFiltersBothRadiosWithAnInclusiveBoundary() {
        val repo=repository(); repo.setScanRange(-60)
        repo.onBle(ble(rssi=-60))
        repo.onBle(ble(name="Weak beacon",rssi=-61,address="AA:BB:CC:DD:EE:02"))
        repo.onWifi(listOf(wifi(),WifiSignal("00:11:22:33:44:66","Weak Wi-Fi",-61,2437,now)))
        repo.publish()
        assertEquals(setOf(SignalSource.BLE,SignalSource.WIFI),repo.visible.value.map { it.source }.toSet())
        assertEquals(2,repo.visible.value.size); assertEquals(4,repo.allDevices().size)
    }
    @Test fun wideningRangeRestoresAlreadyObservedDevicesWithoutNewPackets() {
        val repo=repository(); repo.onBle(ble(rssi=-85)); repo.publish()
        repo.setScanRange(-60); assertTrue(repo.visible.value.isEmpty())
        repo.setScanRange(null); assertEquals(1,repo.visible.value.size)
    }
    @Test fun outsideRangeDoesNotAlertUntilSmoothedSignalEntersRange() {
        val repo=repository(); repo.setScanRange(-60); repo.onBle(ble(rssi=-72))
        assertTrue(repo.alertHistory.value.isEmpty()); assertFalse(repo.allDevices().single().alerted)
        now++; repo.onBle(ble(rssi=-40)); assertTrue(repo.alertHistory.value.isEmpty())
        now++; repo.onBle(ble(rssi=-40)); assertEquals(1,repo.alertHistory.value.size)
    }
    @Test fun baselineRecordsOnlySignalsInsideSelectedRange() {
        val repo=repository(); repo.setScanRange(-60)
        repo.onBle(ble(name="Weak beacon",rssi=-85)); repo.onWifi(listOf(wifi()))
        repo.startBaseline()
        repo.onBle(ble(name="New weak beacon",rssi=-90,address="AA:BB:CC:DD:EE:02"))
        repo.onBle(ble(name="New strong beacon",rssi=-45,address="AA:BB:CC:DD:EE:03"))
        repo.finishBaseline(); repo.setScanRange(null)
        assertEquals(2,repo.allDevices().count { it.isBaseline })
        assertTrue(repo.allDevices().filter { it.rssi < -60 }.none { it.isBaseline })
    }
    @Test fun scanRangeUsesSmoothedRatherThanSinglePacketRssi() {
        val repo=repository(); repo.setScanRange(-60); repo.onBle(ble(rssi=-80)); now++
        repo.onBle(ble(rssi=-40)); repo.publish(); assertTrue(repo.visible.value.isEmpty())
        now++; repo.onBle(ble(rssi=-40)); repo.publish(); assertEquals(1,repo.visible.value.size)
    }
    @Test fun resettingSessionRetainsSelectedScanRange() {
        val repo=repository(); repo.setScanRange(-60); repo.resetSession(); repo.onBle(ble(rssi=-75)); repo.publish()
        assertTrue(repo.visible.value.isEmpty())
    }
    @Test fun customRangeIsBoundedAndAllSignalsHasNoFloor() {
        assertEquals(-100,ScanRange.normalize(-200)); assertEquals(-40,ScanRange.normalize(0))
        assertNull(ScanRange.normalize(null)); assertTrue(ScanRange.includes(-120.0,null))
    }
    @Test fun reportMarksOutOfRangeObservationsWithoutLosingSessionHistory() {
        val repo=repository(); repo.onBle(ble(rssi=-85)); repo.onWifi(listOf(wifi()))
        val csv=SessionCsv.encode(repo.allDevices(),false,-60)
        assertTrue(csv.lineSequence().first().endsWith("scan_minimum_rssi_dbm,within_selected_range"))
        assertTrue(csv.contains("\"-60\",\"false\"")); assertTrue(csv.contains("\"-60\",\"true\""))
    }
}
