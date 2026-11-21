@file:Suppress("DEPRECATION")
package com.examscan

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.EditText
import com.examscan.scan.ScanPermissions
import com.examscan.scan.ChannelStatus
import com.examscan.ui.MainActivity
import com.examscan.ui.ScanViewModel
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.Espresso.pressBack
import com.examscan.scan.FinderStatus
import org.hamcrest.CoreMatchers.`is`
import java.io.File

/** Platform instrumentation: exercise real Activity, controller, baseline, navigation, and filtering. */
@RunWith(AndroidJUnit4::class)
class ScannerUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    @Before fun setUp() {
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.model.setScanRange(null) }
    }
    @After fun tearDown() { instrumentation.runOnMainSync { activity.model.setScanRange(null); activity.finish() } }
    private fun allViews(view:View):List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
    private fun tap(label:String) {
        instrumentation.runOnMainSync {
            val view=allViews(activity.window.decorView).firstOrNull { it is TextView && it.text.toString()==label && it.isClickable }
                ?: allViews(activity.window.decorView).first { it.contentDescription?.toString()==label && it.isClickable }
            view.performClick()
        }
        instrumentation.waitForIdleSync()
    }
    @Test fun testDemoBaselineAndNavigation() {
        tap(activity.getString(R.string.try_demo))
        assertTrue(activity.model.demo.value)
        assertEquals(7,activity.model.sessionLog().size)
        assertTrue(activity.model.scanning.value)
        instrumentation.runOnMainSync { activity.model.stop() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250) // Let the compositor present the paused-state frame before capture.
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null),"scanner-demo.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
        tap(activity.getString(R.string.ble))
        instrumentation.runOnMainSync {
            val cards=allViews(activity.window.decorView).filter { it.tag?.toString()?.startsWith("ble:")==true }
            assertEquals(5,cards.size)
            val search=allViews(activity.window.decorView).filterIsInstance<EditText>().single()
            search.setText("Garmin")
            assertEquals(1,allViews(activity.window.decorView).count { it.tag?.toString()?.startsWith("ble:")==true })
            search.setText("")
        }
        tap(activity.getString(R.string.session))
        instrumentation.runOnMainSync { activity.model.start(); activity.model.startBaseline(); activity.model.finishBaseline() }
        assertTrue(activity.model.devices.value.all { it.isBaseline })
        tap(activity.getString(R.string.devices))
        tap(activity.getString(R.string.baseline))
        tap(activity.getString(R.string.alerts))
        assertFalse(activity.model.alertHistory.value.isEmpty())
        instrumentation.runOnMainSync { activity.model.resetSession() }
        assertTrue(activity.model.sessionLog().isEmpty())
    }
    @Test fun testDemoStopsWhenActivityLeavesForeground() {
        tap(activity.getString(R.string.try_demo))
        assertTrue(activity.model.scanning.value)
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity); instrumentation.callActivityOnStop(activity) }
        assertFalse(activity.model.scanning.value)
    }
    @Test fun testPlatformScannersStartAndCleanUpWithGrantedPermissions() {
        ScanPermissions.required().forEach { permission ->
            val command=instrumentation.uiAutomation.executeShellCommand("pm grant ${activity.packageName} $permission")
            command.close()
        }
        val location=instrumentation.uiAutomation.executeShellCommand("cmd location set-location-enabled true")
        location.close()
        SystemClock.sleep(300)
        assertTrue(ScanPermissions.allGranted(activity))
        instrumentation.runOnMainSync { activity.model.start() }
        SystemClock.sleep(1500)
        assertTrue(activity.model.scanning.value)
        assertNotEquals(ChannelStatus.ERROR,activity.model.bleStatus.value)
        assertNotEquals(ChannelStatus.ERROR,activity.model.wifiStatus.value)
        instrumentation.runOnMainSync { activity.model.stop() }
        assertFalse(activity.model.scanning.value)
    }
    @Test fun testRangePickerFiltersLiveSessionAndPersistsChoice() {
        tap(activity.getString(R.string.try_demo))
        instrumentation.runOnMainSync { activity.model.stop() }
        onView(withTagValue(`is`("scan-range"))).perform(scrollTo(),click())
        onView(withTagValue(`is`("range-option-1"))).perform(scrollTo(),click())
        SystemClock.sleep(150)
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null),"scanner-range.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        onView(withText(R.string.range_apply)).perform(click())
        assertEquals(-60,activity.model.scanRange.value)
        assertEquals(3,activity.model.devices.value.size)
        assertEquals(7,activity.model.sessionLog().size)
        instrumentation.runOnMainSync {
            val restored=ScanViewModel(activity)
            assertEquals(-60,restored.scanRange.value); restored.close()
        }
        onView(withTagValue(`is`("scan-range"))).perform(scrollTo(),click())
        onView(withTagValue(`is`("range-option-0"))).perform(scrollTo(),click())
        onView(withText(R.string.range_apply)).perform(click())
        assertEquals(7,activity.model.devices.value.size)
    }
    @Test fun testFinderFollowsSelectedDeviceOutsideRangeAndHandlesPauseAndBack() {
        tap(activity.getString(R.string.try_demo))
        instrumentation.runOnMainSync { activity.model.stop() }
        val key="ble:02:DE:00:00:00:01"
        onView(withTagValue(`is`(key))).perform(scrollTo(),click())
        onView(withTagValue(`is`("find-device"))).perform(scrollTo(),click())
        assertEquals(key,activity.model.finding.value?.device?.key)
        assertTrue(activity.model.scanning.value)
        onView(withTagValue(`is`("finder-target"))).check(matches(withText("AirPods Pro")))
        instrumentation.runOnMainSync { activity.model.setScanRange(-40) }
        assertTrue(activity.model.devices.value.isEmpty())
        SystemClock.sleep(2_600)
        assertEquals(key,activity.model.finding.value?.device?.key)
        assertNotNull(activity.model.finding.value?.reading?.currentRssi)
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null),"scanner-finder.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        onView(withTagValue(`is`("finder-scan"))).perform(scrollTo(),click())
        assertEquals(FinderStatus.PAUSED,activity.model.finding.value?.reading?.status)
        onView(withTagValue(`is`("finder-scan"))).perform(scrollTo(),click())
        assertTrue(activity.model.scanning.value)
        assertEquals(key,activity.model.finding.value?.device?.key)
        pressBack()
        assertNull(activity.model.finding.value)
        assertFalse(activity.isFinishing)
    }
    @Test fun testWifiFinderKeepsIdentityAndPausesWhenLeavingForeground() {
        tap(activity.getString(R.string.try_demo))
        val key="wifi:00:DE:00:00:00:06"
        onView(withTagValue(`is`(key))).perform(scrollTo(),click())
        onView(withTagValue(`is`("find-device"))).perform(scrollTo(),click())
        assertEquals(key,activity.model.finding.value?.device?.key)
        onView(withText(R.string.find_wifi_note)).perform(scrollTo())
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(activity); instrumentation.callActivityOnStop(activity) }
        assertEquals(FinderStatus.PAUSED,activity.model.finding.value?.reading?.status)
        assertFalse(activity.model.scanning.value)
        instrumentation.runOnMainSync { activity.model.resetSession() }
        assertNull(activity.model.finding.value)
    }
}
