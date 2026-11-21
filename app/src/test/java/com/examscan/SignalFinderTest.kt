package com.examscan

import com.examscan.scan.*
import com.examscan.signature.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class SignalFinderTest {
    private val start = 100_000L
    private fun device(at:Long, strength:Double, source:SignalSource=SignalSource.BLE) = DetectedDevice(
        "${source.name}:test",source,"Target",Classification(null,"unknown","Unknown",RiskLevel.LOW),
        strength.toInt(),strength,at,at,1,false,false)
    private fun feed(tracker:SignalFinder, values:List<Double>):FinderReading = values.mapIndexed { i,v ->
        val time=start+i*1_000; tracker.update(device(time,v),time,true)
    }.last()

    @Test fun initiallyCollectsWithoutInventingATrend() {
        val r=SignalFinder(SignalSource.BLE).update(device(start,-60.0),start,true)
        assertEquals(FinderStatus.COLLECTING,r.status); assertNull(r.changeDb); assertEquals(1,r.points.size)
    }
    @Test fun sustainedIncreaseIsStronger() {
        val r=feed(SignalFinder(SignalSource.BLE),listOf(-80.0,-80.0,-80.0,-60.0,-60.0,-60.0))
        assertEquals(FinderStatus.STRONGER,r.status); assertEquals(20.0,r.changeDb!!,0.001)
    }
    @Test fun sustainedDecreaseIsWeaker() {
        val r=feed(SignalFinder(SignalSource.BLE),listOf(-55.0,-55.0,-55.0,-75.0,-75.0,-75.0))
        assertEquals(FinderStatus.WEAKER,r.status); assertEquals(-20.0,r.changeDb!!,0.001)
    }
    @Test fun smallVariationRemainsSteady() {
        val r=feed(SignalFinder(SignalSource.BLE),listOf(-70.0,-69.0,-71.0,-70.0,-69.0,-70.0))
        assertEquals(FinderStatus.STEADY,r.status)
    }
    @Test fun repeatedCachedPacketDoesNotCreateComparisonSamples() {
        val tracker=SignalFinder(SignalSource.BLE)
        val d=device(start,-70.0)
        repeat(10) { tracker.update(d,start+it*1_000,true) }
        val r=tracker.update(d,start+10_000,true)
        assertEquals(1,r.points.size); assertEquals(FinderStatus.COLLECTING,r.status)
    }
    @Test fun staleBleReportsLostAndKeepsLastReceivedValue() {
        val tracker=SignalFinder(SignalSource.BLE); val d=device(start,-70.0)
        tracker.update(d,start,true)
        val r=tracker.update(d,start+15_001,true)
        assertEquals(FinderStatus.LOST,r.status); assertEquals(-70.0,r.currentRssi!!,0.0)
        assertEquals(15_001L,r.ageMs); assertNull(r.changeDb)
    }
    @Test fun wifiWaitsForItsSlowerScanCycle() {
        val tracker=SignalFinder(SignalSource.WIFI); val d=device(start,-70.0,SignalSource.WIFI)
        tracker.update(d,start,true)
        assertEquals(FinderStatus.COLLECTING,tracker.update(d,start+35_000,true).status)
        assertEquals(FinderStatus.LOST,tracker.update(d,start+90_001,true).status)
    }
    @Test fun wifiComparesDistinctScanTimestamps() {
        val tracker=SignalFinder(SignalSource.WIFI)
        tracker.update(device(start,-80.0,SignalSource.WIFI),start,true)
        val r=tracker.update(device(start+35_000,-65.0,SignalSource.WIFI),start+35_000,true)
        assertEquals(FinderStatus.STRONGER,r.status); assertEquals(15.0,r.changeDb!!,0.0)
    }
    @Test fun reacquiredSignalStartsANewComparisonWindow() {
        val tracker=SignalFinder(SignalSource.BLE)
        feed(tracker,listOf(-80.0,-80.0,-80.0,-60.0,-60.0,-60.0))
        val r=tracker.update(device(start+25_000,-70.0),start+25_000,true)
        assertEquals(FinderStatus.COLLECTING,r.status); assertEquals(1,r.points.size)
    }
    @Test fun pausedNeverPresentsAnActiveTrend() {
        val tracker=SignalFinder(SignalSource.BLE)
        feed(tracker,listOf(-80.0,-80.0,-80.0,-60.0,-60.0,-60.0))
        val r=tracker.update(device(start+5_000,-60.0),start+6_000,false)
        assertEquals(FinderStatus.PAUSED,r.status); assertNull(r.changeDb)
    }
    @Test fun unavailableRadioDoesNotClaimMovement() {
        val r=SignalFinder(SignalSource.BLE).update(device(start,-60.0),start,true,false)
        assertEquals(FinderStatus.UNAVAILABLE,r.status); assertNull(r.changeDb)
    }
    @Test fun fastAdvertisingIsLimitedToOnePointPerSecond() {
        val tracker=SignalFinder(SignalSource.BLE)
        repeat(50) { i -> tracker.update(device(start+i*10,-60.0),start+i*10,true) }
        val r=tracker.update(device(start+500,-60.0),start+500,true)
        assertEquals(1,r.points.size); assertEquals(FinderStatus.COLLECTING,r.status)
    }
    @Test fun bestSignalAndHistoryStayBounded() {
        val tracker=SignalFinder(SignalSource.BLE)
        var r=tracker.update(device(start,-40.0),start,true)
        repeat(200) { i -> val at=start+(i+1)*1_000; r=tracker.update(device(at,-75.0),at,true) }
        assertEquals(-40.0,r.bestRssi!!,0.0); assertTrue(r.points.size<=90)
    }
    @Test fun futureAndOlderTimestampsDoNotRefreshTheSignal() {
        val tracker=SignalFinder(SignalSource.BLE)
        val future=tracker.update(device(start+2_000,-50.0),start,true)
        assertNull(future.lastSeenMs)
        tracker.update(device(start,-70.0),start,true)
        val old=tracker.update(device(start-1,-40.0),start+1000,true)
        assertEquals(start,old.lastSeenMs); assertEquals(-70.0,old.currentRssi!!,0.0)
    }
    @Test fun pausedDoesNotIngestNewReadings() {
        val tracker=SignalFinder(SignalSource.BLE)
        tracker.update(device(start,-70.0),start,true)
        val r=tracker.update(device(start+5_000,-40.0),start+5_000,false)
        assertEquals(-70.0,r.currentRssi!!,0.0); assertEquals(start,r.lastSeenMs)
    }
    @Test fun sparseBleReadingsCanStillFormATrend() {
        val tracker=SignalFinder(SignalSource.BLE)
        var r=tracker.update(device(start,-80.0),start,true)
        listOf(-80.0,-60.0,-60.0).forEachIndexed { i,v ->
            val at=start+(i+1)*2_000; r=tracker.update(device(at,v),at,true)
        }
        assertEquals(FinderStatus.STRONGER,r.status)
    }
}
