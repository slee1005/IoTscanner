@file:Suppress("DEPRECATION")
package com.examscan

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.examscan.scan.*
import com.examscan.ui.MainActivity
import com.examscan.ui.ScanViewModel
import org.hamcrest.CoreMatchers.`is`
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Opt-in recording fixture, excluded from normal runs unless -e captureMedia true is passed.
 * All devices and RSSI transitions are synthetic. No production scanning behavior is changed.
 */
@RunWith(AndroidJUnit4::class)
class MediaCaptureTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var output: File
    private var recording: ParcelFileDescriptor? = null
    private var origin = 0L
    private val chapters = JSONArray()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        inst.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun ui(block: () -> Unit) { inst.runOnMainSync(block); inst.waitForIdleSync() }
    private fun hold(ms: Long) { SystemClock.sleep(ms) }
    private fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun top() = ui { views(activity.window.decorView).filterIsInstance<ScrollView>().firstOrNull()?.scrollTo(0,0) }
    private fun tap(id: Int) {
        if(id in listOf(R.string.devices,R.string.alerts,R.string.session)) onView(withContentDescription(id)).perform(click())
        else onView(withText(id)).perform(click())
        hold(250)
    }
    private fun tag(value: String) {
        val view=onView(withTagValue(`is`(value)))
        if(value=="stop-finding") view.perform(click()) else view.perform(scrollTo(),click())
        hold(250)
    }
    private fun screenshot(name: String) {
        inst.waitForIdleSync(); hold(300)
        val bitmap = inst.uiAutomation.takeScreenshot()
        File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun chapter(id: String, action: () -> Unit) {
        inst.sendStatus(0, Bundle().apply { putString("stream","\n[media] $id\n") })
        val start = SystemClock.elapsedRealtime()-origin
        action()
        chapters.put(JSONObject().put("id",id).put("start_ms",start)
            .put("end_ms",SystemClock.elapsedRealtime()-origin))
    }
    @Test fun captureEnglishWalkthrough() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureMedia") == "true")
        Locale.setDefault(Locale.US)
        shell("settings put global sysui_demo_allowed 1")
        shell("am broadcast -a com.android.systemui.demo --es command enter")
        shell("am broadcast -a com.android.systemui.demo --es command clock --es hhmm 0941")
        shell("am broadcast -a com.android.systemui.demo --es command notifications --es visible false")
        shell("am broadcast -a com.android.systemui.demo --es command battery --es level 100 --es plugged false")
        activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        output=File(inst.targetContext.getExternalFilesDir(null),"media-capture").apply { mkdirs() }
        ui { activity.model.setScanRange(null); activity.model.setSound(false); activity.model.startDemo() }
        hold(1_000)
        origin=SystemClock.elapsedRealtime()
        recording=inst.uiAutomation.executeShellCommand(
            "screenrecord --bit-rate 8000000 --size 720x1600 --time-limit 180 ${File(output,"walkthrough-raw.mp4").absolutePath}")
        hold(1_000)
        try {
            chapter("discover") {
                top(); screenshot("01-nearby-devices"); hold(3_000)
                tag("wifi:00:DE:00:00:00:06"); hold(2_000)
                tap(R.string.close); top(); hold(2_000)
            }
            chapter("range") {
                ui { activity.model.stop() }
                tag("scan-range"); tag("range-option-1")
                screenshot("02-scan-range"); hold(2_500)
                tap(R.string.range_apply); top(); screenshot("03-nearby-range"); hold(2_500)
                tag("scan-range"); tag("range-option-0"); tap(R.string.range_apply)
                ui { activity.model.start() }; hold(500)
            }
            chapter("alerts") {
                tap(R.string.alerts); top(); screenshot("04-alerts"); hold(6_000)
            }
            chapter("baseline") {
                tap(R.string.session)
                tag("baseline"); screenshot("05-baseline"); hold(3_000)
                tag("baseline"); top(); hold(3_000)
            }
            chapter("finder") {
                tap(R.string.devices)
                ui { activity.model.stop() }
                // Control the advertised demo signal to show stronger/weaker transitions clearly.
                // Reflection is confined to this opt-in test fixture; production app code is unchanged.
                val field=ScanViewModel::class.java.getDeclaredField("repo").apply { isAccessible=true }
                val repo=field.get(activity.model) as DetectionRepository
                fun sample(strength: Int) = ui {
                    repo.onBle(BleSignal("02:DE:00:00:00:01","AirPods Pro",strength,null,
                        mapOf(0x004c to byteArrayOf(7,1,25)),emptySet(),System.currentTimeMillis()))
                    repo.publish()
                }
                repeat(15) { sample(-83); hold(12) }
                tag("ble:02:DE:00:00:00:01")
                ui { activity.model.scanning.value=true; activity.model.bleStatus.value=ChannelStatus.SCANNING }
                tag("find-device")
                val began=SystemClock.elapsedRealtime()
                var strongerSaved=false; var weakerSaved=false
                while(SystemClock.elapsedRealtime()-began<25_000) {
                    val elapsed=SystemClock.elapsedRealtime()-began
                    sample(when { elapsed<5_000 -> -83; elapsed<15_000 -> -45; else -> -85 })
                    val state=activity.model.finding.value?.reading?.status
                    if(!strongerSaved && elapsed>9_000 && state==FinderStatus.STRONGER) {
                        screenshot("06-find-stronger"); strongerSaved=true
                    }
                    if(!weakerSaved && elapsed>20_000 && state==FinderStatus.WEAKER) {
                        screenshot("07-find-weaker"); weakerSaved=true
                    }
                    hold(350)
                }
                check(strongerSaved && weakerSaved) { "Both real UI trend states must appear in the recording" }
            }
            chapter("report") {
                tag("stop-finding"); tap(R.string.session)
                onView(withText(R.string.export_csv)).perform(scrollTo())
                screenshot("08-session-report"); hold(7_000)
                File(output,"sample-session-demo.csv").writeText(SessionCsv.encode(
                    activity.model.sessionLog(),true,activity.model.scanRange.value))
            }
        } finally {
            shell("pkill -2 screenrecord")
            recording?.let { fd ->
                val log=ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
                File(output,"screenrecord.log").writeText(log)
            }
            File(output,"chapters.json").writeText(JSONObject().put("language","en-US")
                .put("simulated",true).put("chapters",chapters).toString(2))
            ui { activity.model.stop(); activity.finish() }
        }
    }
}
