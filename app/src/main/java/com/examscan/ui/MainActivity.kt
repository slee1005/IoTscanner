package com.examscan.ui

import android.app.*
import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.examscan.R
import com.examscan.scan.*
import com.examscan.signature.RiskLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : Activity() {
    lateinit var model: ScanViewModel
        private set
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var shell: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var navigation: LinearLayout
    private var tab = 0
    private var filter = 0
    private var query = ""
    private var countText: TextView? = null
    private var stateText: TextView? = null
    private var scanButton: TextView? = null
    private var bleText: TextView? = null
    private var wifiText: TextView? = null
    private var channelRow: LinearLayout? = null
    private var demoText: TextView? = null
    private var baselineBanner: TextView? = null
    private var radar: RadarView? = null
    private var stats: LinearLayout? = null
    private var results: LinearLayout? = null
    private var baselineButton: TextView? = null
    private var baselineBody: TextView? = null
    private var rangeButton: TextView? = null
    private var pendingExport: String? = null
    private var selectedKey: String? = null
    private var detailStrength: TextView? = null
    private var detailChart: SignalChart? = null
    private var detailLast: TextView? = null
    private var detailSamples: TextView? = null
    private var lastRows: Any? = null
    private var showingFinderKey: String? = null
    private var finderTrend: TextView? = null
    private var finderStrength: TextView? = null
    private var finderChange: TextView? = null
    private var finderLast: TextView? = null
    private var finderBest: TextView? = null
    private var finderHint: TextView? = null
    private var finderChart: SignalChart? = null
    private var finderMeter: ProgressBar? = null
    private var finderAction: TextView? = null
    private var finderOutsideRange: TextView? = null
    private var finderBackCallback: OnBackInvokedCallback? = null
    private val matchParent = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun s(id: Int, vararg args: Any) = getString(id, *args)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        model = lastNonConfigurationInstance as? ScanViewModel ?: ScanViewModel(this)
        tab = savedInstanceState?.getInt("tab") ?: 0
        filter = savedInstanceState?.getInt("filter") ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        pendingExport = savedInstanceState?.getString("pendingExport")
        window.statusBarColor = Palette.bg; window.navigationBarColor = Palette.bg
        window.decorView.systemUiVisibility = 0
        shell = column().apply { setBackgroundColor(Palette.bg) }
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        buildScreen()
        uiScope.launch {
            combine(model.devices, model.scanning, model.alertHistory, model.isRecordingBaseline,
                model.bleStatus, model.wifiStatus, model.demo, model.permissionNeeded) { Unit }
                .collect { render() }
        }
        uiScope.launch {
            model.alerts.collect {
                if (model.sound) runCatching {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
                    launch { delay(350); tone.release() }
                }
            }
        }
        uiScope.launch { model.scanRange.collect { render() } }
        uiScope.launch { model.finding.collect { render() } }
    }
    override fun onRetainNonConfigurationInstance(): Any = model
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab); outState.putInt("filter", filter); outState.putString("query", query)
        outState.putString("pendingExport", pendingExport)
    }
    override fun onStop() { model.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop() }
    override fun onDestroy() {
        if(Build.VERSION.SDK_INT>=33) finderBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        uiScope.cancel(); if (!isChangingConfigurations) model.close(); super.onDestroy()
    }
    @Deprecated("Legacy back navigation on Android 12 and earlier")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the registered OnBackInvokedCallback; this is the API 26–32 fallback.
    override fun onBackPressed() {
        if(model.finding.value!=null) model.stopFinding() else super.onBackPressed()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Int = 14, color: Int = Palette.text, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun rounded(color: Int, radius: Int = 18, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1), it) }
    }
    private fun space(parent: LinearLayout, height: Int = 16) = parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    private fun add(parent: LinearLayout, child: View, height: Int = wrapContent) = parent.addView(child,
        LinearLayout.LayoutParams(matchParent, if (height >= 0) dp(height) else height))
    private fun card(parent: LinearLayout, block: (LinearLayout) -> Unit): LinearLayout {
        val card = column().apply { background = rounded(Palette.surface, 18, Palette.line); setPadding(dp(18),dp(18),dp(18),dp(18)) }
        block(card); add(parent,card); return card
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): TextView =
        text(label, 15, if (primary) Palette.bg else Palette.mint, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(50); setPadding(dp(14), dp(10), dp(14), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
                rounded(if(primary) Palette.mint else Palette.surface, 13, if(primary) null else Palette.line), null)
            isClickable = true; isFocusable = true; setOnClickListener { action() }
        }
    private fun pill(value: String, color: Int = Palette.mint) = text(value, 10, color, true).apply {
        setPadding(dp(9), dp(5), dp(9), dp(5)); background = rounded(Color.argb(20, Color.red(color), Color.green(color), Color.blue(color)), 7)
    }

    private fun buildScreen() {
        countText=null; stateText=null; scanButton=null; bleText=null; wifiText=null; demoText=null; channelRow=null
        baselineBanner=null; radar=null; stats=null; results=null; baselineButton=null; baselineBody=null; rangeButton=null; lastRows=null
        finderTrend=null; finderStrength=null; finderChange=null; finderLast=null; finderBest=null; finderHint=null
        finderChart=null; finderMeter=null; finderAction=null; finderOutsideRange=null
        val finding=model.finding.value
        showingFinderKey=finding?.device?.key
        if(Build.VERSION.SDK_INT>=33) {
            finderBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            finderBackCallback=null
            if(finding!=null) {
                finderBackCallback=OnBackInvokedCallback { model.stopFinding() }.also {
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)
                }
            }
        }
        shell.removeAllViews()
        val brand = row().apply { setPadding(dp(20), dp(12), dp(20), dp(8)) }
        brand.addView(GlyphView(this,"radar"), LinearLayout.LayoutParams(dp(32),dp(32)))
        brand.addView(text(s(R.string.app_name),19, bold=true).apply { setPadding(dp(9),0,0,0) }, LinearLayout.LayoutParams(0,wrapContent,1f))
        brand.addView(pill(s(R.string.offline)).apply { contentDescription = s(R.string.local_title); setOnClickListener { info() } })
        add(shell,brand)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body=column().apply { setPadding(dp(20),dp(16),dp(20),dp(24)) }
        scroll.addView(body); shell.addView(scroll, LinearLayout.LayoutParams(matchParent,0,1f))
        val title = if(finding!=null) R.string.find_title else when(tab) { 1->R.string.alerts_title; 2->R.string.session_title; else->R.string.nearby_title }
        val subtitle = when(tab) { 1->R.string.alerts_subtitle; 2->R.string.session_subtitle; else->R.string.nearby_subtitle }
        add(body,text(s(title),29,bold=true))
        if(finding==null && tab != 0) { space(body,5); add(body,text(s(subtitle),14,Palette.muted)) }
        space(body,16)
        demoText = pill(s(R.string.demo), Palette.amber).also { add(body,it); space(body,12) }
        if(finding!=null) buildFinder(finding) else when(tab) { 0->buildDevices(); 1->buildAlerts(); else->buildSession() }
        navigation = row().apply { background=rounded(Palette.bg,0,Palette.line); setPadding(dp(8),dp(9),dp(8),dp(7)) }
        if(finding==null) { add(shell,navigation); updateNavigation() }
        else {
            val footer=column().apply { setPadding(dp(20),dp(8),dp(20),dp(8)) }
            add(footer,button(s(R.string.find_done)) { model.stopFinding() }.apply { tag="stop-finding" })
            add(shell,footer)
        }
        render()
    }
    private fun updateNavigation() {
        navigation.removeAllViews()
        listOf(R.string.devices,R.string.alerts,R.string.session).forEachIndexed { index,id ->
            val color=if(index==tab) Palette.mint else Palette.muted
            val nav=column().apply { gravity=Gravity.CENTER; minimumHeight=dp(54); isClickable=true; isFocusable=true
                contentDescription=s(id); setOnClickListener { tab=index; buildScreen() } }
            nav.addView(GlyphView(this,listOf("radar","alerts","session")[index],color), LinearLayout.LayoutParams(dp(25),dp(25)))
            val label = if(index==1 && model.alertHistory.value.isNotEmpty()) "${s(id)} · ${model.alertHistory.value.size}" else s(id)
            nav.addView(text(label,11,color,index==tab))
            navigation.addView(nav, LinearLayout.LayoutParams(0,wrapContent,1f))
        }
    }
    private fun buildDevices() {
        val hero=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF183832.toInt(),0xFF10242A.toInt())).apply { cornerRadius=dp(20).toFloat(); setStroke(dp(1),0xFF295047.toInt()) }
            setPadding(dp(16),dp(12),dp(12),dp(12))
        }
        val heroText=column()
        stateText=text(s(R.string.ready),11,Palette.mint,true).apply { maxLines=2 }.also { add(heroText,it) }
        countText=text("00",38,bold=true).apply { includeFontPadding=false }.also { add(heroText,it) }
        add(heroText,text(s(R.string.signals_visible),12,Palette.muted))
        hero.addView(heroText,LinearLayout.LayoutParams(0,wrapContent,1f))
        radar=RadarView(this).also { hero.addView(it,LinearLayout.LayoutParams(dp(66),dp(66))) }
        scanButton=button(s(R.string.scan_short),true) { toggleScan() }.also {
            it.tag="scan"; it.textSize=13f; it.setPadding(dp(10),dp(10),dp(10),dp(10))
            hero.addView(it,LinearLayout.LayoutParams(dp(72),dp(48)).apply { marginStart=dp(6) })
        }
        add(body,hero)
        rangeButton = text("",12,Palette.mint,true).apply {
            minHeight=dp(48); gravity=Gravity.CENTER_VERTICAL
            isClickable=true; isFocusable=true; tag="scan-range"; contentDescription=s(R.string.range_title)
            setOnClickListener { showScanRange() }
        }.also { add(body,it) }
        val channels=row()
        bleText=text("",11,Palette.muted).apply { setOnClickListener { radioSettings() }; minHeight=dp(40) }.also { channels.addView(it,LinearLayout.LayoutParams(0,wrapContent,1f)) }
        wifiText=text("",11,Palette.muted).apply { setOnClickListener { radioSettings() }; minHeight=dp(40); gravity=Gravity.END }.also { channels.addView(it,LinearLayout.LayoutParams(0,wrapContent,1f)) }
        channelRow=channels; add(body,channels)
        baselineBanner=pill(s(R.string.recording_baseline)).also { add(body,it) }
        space(body,8)
        val search=EditText(this).apply {
            hint=s(R.string.search_hint); textSize=14f; setTextColor(Palette.text); setHintTextColor(Palette.muted)
            setSingleLine(); imeOptions=EditorInfo.IME_ACTION_DONE; inputType=android.text.InputType.TYPE_CLASS_TEXT
            background=rounded(Palette.surface,12,Palette.line); setPadding(dp(14),0,dp(14),0)
            setText(query); contentDescription=s(R.string.search_hint)
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s.toString(); updateRows() }
                override fun afterTextChanged(s:Editable?) {}
            })
        }
        add(body,search,48); space(body,12)
        val filterScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        val filterRow=row()
        listOf(R.string.all,R.string.ble,R.string.wifi,R.string.priority,R.string.baseline).forEachIndexed { index,id ->
            val chip=text(s(id),12,if(filter==index) Palette.bg else Palette.muted,true).apply {
                gravity=Gravity.CENTER; minHeight=dp(48); setPadding(dp(14),0,dp(14),0)
                background=rounded(if(filter==index) Palette.mint else Palette.surface,12)
                isClickable=true; isFocusable=true; setOnClickListener { filter=index; buildScreen() }
            }
            filterRow.addView(chip,LinearLayout.LayoutParams(wrapContent,dp(48)).apply { marginEnd=dp(7) })
        }
        filterScroll.addView(filterRow); add(body,filterScroll); space(body,20)
        add(body,text(s(R.string.active_signals),11,Palette.muted,true)); space(body,12)
        results=column().also { add(body,it) }
    }
    private fun buildFinder(session: FindingSession) {
        val device=session.device
        add(body,text(device.displayName,21,bold=true).apply { tag="finder-target"; maxLines=2 })
        space(body,5)
        add(body,text(s(R.string.find_identity,if(device.source==SignalSource.BLE) "BLE" else "Wi-Fi",
            device.key.substringAfter(':')),12,Palette.muted).apply { setTextIsSelectable(true) })
        space(body,14)
        card(body) { c ->
            finderTrend=text("",22,Palette.mint,true).apply {
                tag="finder-trend"; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { add(c,it) }
            space(c,8)
            finderStrength=text("",43,bold=true).apply { tag="finder-strength" }.also { add(c,it) }
            finderMeter=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100; progressTintList=ColorStateList.valueOf(Palette.mint)
                progressBackgroundTintList=ColorStateList.valueOf(Palette.line)
                contentDescription=s(R.string.signal_strength)
            }.also { add(c,it,18) }
            val labels=row()
            labels.addView(text(s(R.string.find_weaker_label),11,Palette.muted),LinearLayout.LayoutParams(0,wrapContent,1f))
            labels.addView(text(s(R.string.find_stronger_label),11,Palette.muted)); add(c,labels)
            space(c,12)
            finderChange=text("",13,Palette.muted).also { add(c,it) }
            finderLast=text("",12,Palette.muted).apply { tag="finder-last-seen" }.also { add(c,it) }
            finderBest=text("",12,Palette.muted).also { add(c,it) }
        }
        space(body,12)
        finderAction=button("") { toggleScan() }.apply { tag="finder-scan" }.also { add(body,it) }
        space(body,16)
        add(body,text(s(R.string.find_history),11,Palette.muted,true)); space(body,8)
        finderChart=SignalChart(this).also { add(body,it,85) }
        space(body,12)
        finderHint=text("",14,Palette.muted).also { add(body,it) }
        finderOutsideRange=text(s(R.string.find_outside_range),12,Palette.amber).also { add(body,it) }
        space(body,14)
        if(device.source==SignalSource.WIFI) { add(body,text(s(R.string.find_wifi_note),12,Palette.amber)); space(body,10) }
        add(body,text(s(R.string.find_note),12,Palette.muted))
    }
    private fun updateFinder(session: FindingSession) {
        val reading=session.reading
        val label=when(reading.status) {
            FinderStatus.COLLECTING->R.string.find_collecting
            FinderStatus.STRONGER->R.string.find_stronger
            FinderStatus.WEAKER->R.string.find_weaker
            FinderStatus.STEADY->R.string.find_steady
            FinderStatus.LOST->R.string.find_lost
            FinderStatus.PAUSED->R.string.find_paused
            FinderStatus.UNAVAILABLE->R.string.find_unavailable
        }
        val tint=when(reading.status) {
            FinderStatus.STRONGER->Palette.mint
            FinderStatus.WEAKER,FinderStatus.LOST,FinderStatus.UNAVAILABLE->Palette.amber
            else->Palette.muted
        }
        if(finderTrend?.text?.toString()!=s(label)) finderTrend?.text=s(label)
        finderTrend?.setTextColor(tint)
        finderStrength?.text=reading.currentRssi?.let { s(R.string.signal_value,it.roundToInt()) } ?: s(R.string.find_no_reading)
        val fresh=reading.status !in setOf(FinderStatus.PAUSED,FinderStatus.LOST,FinderStatus.UNAVAILABLE)
        finderStrength?.setTextColor(if(fresh) Palette.text else Palette.muted)
        finderChange?.text=reading.changeDb?.let { s(R.string.find_change,it) } ?: s(R.string.find_wait_comparison)
        finderLast?.text=when {
            reading.lastSeenMs==null->s(R.string.find_wait_signal)
            reading.status==FinderStatus.PAUSED->s(R.string.find_last_time,
                SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(reading.lastSeenMs)))
            else->s(R.string.last_seen_value,(reading.ageMs ?: 0)/1000)
        }
        finderBest?.text=reading.bestRssi?.let { s(R.string.find_best,it.roundToInt()) }.orEmpty()
        finderMeter?.progress=if(fresh) (((reading.currentRssi ?: -100.0)+100)/65*100).roundToInt().coerceIn(0,100) else 0
        finderChart?.points=reading.points.map { it.rssi.roundToInt() }
        finderAction?.text=s(if(model.scanning.value) R.string.stop_scan else R.string.continue_scan)
        finderHint?.text=s(when(reading.status) {
            FinderStatus.LOST->R.string.find_lost_hint
            FinderStatus.PAUSED->R.string.find_paused_hint
            FinderStatus.UNAVAILABLE->R.string.find_unavailable_hint
            else->R.string.find_move_hint
        })
        finderOutsideRange?.visibility=if(!ScanRange.includes(session.device.smoothedRssi,model.scanRange.value)) View.VISIBLE else View.GONE
    }
    private fun buildAlerts() {
        val effective = maxOf(model.threshold, model.scanRange.value ?: Int.MIN_VALUE)
        card(body) { add(it,text(s(R.string.alert_rule,effective),13,Palette.muted)) }; space(body,20)
        results=column().also { add(body,it) }
    }
    private fun buildSession() {
        stats=row().also { add(body,it) }; space(body,12)
        card(body) { c ->
            add(c,text(s(R.string.range_title),18,bold=true)); space(c,8)
            add(c,text(s(R.string.range_body),13,Palette.muted)); space(c,12)
            rangeButton=button("") { showScanRange() }.also { it.contentDescription=s(R.string.range_title); it.tag="scan-range"; add(c,it) }
        }; space(body)
        card(body) { c ->
            add(c,text(s(R.string.baseline_title),18,bold=true)); space(c,9)
            baselineBody=text(s(R.string.baseline_body),13,Palette.muted).also { add(c,it) }; space(c,15)
            baselineButton=button(s(R.string.record_baseline),true) {
                if(model.isRecordingBaseline.value) model.finishBaseline()
                else if(model.scanning.value) model.startBaseline()
                else Toast.makeText(this,s(R.string.start_first),Toast.LENGTH_LONG).show()
            }.also { it.tag="baseline"; add(c,it) }
        }; space(body)
        card(body) { c ->
            add(c,text(s(R.string.threshold_title),17,bold=true)); space(c,8)
            add(c,text(s(R.string.threshold_body),13,Palette.muted)); space(c,12)
            val value=text(s(R.string.signal_value,model.threshold),24,Palette.mint,true); add(c,value)
            val slider=SeekBar(this).apply {
                max=60; progress=model.threshold+100; contentDescription=s(R.string.threshold_title)
                progressTintList=ColorStateList.valueOf(Palette.mint); thumbTintList=ColorStateList.valueOf(Palette.mint)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                        if(fromUser) { model.setAlertThreshold(progress-100); value.text=s(R.string.signal_value,model.threshold) }
                    }
                    override fun onStartTrackingTouch(seekBar:SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar:SeekBar?) {}
                })
            }; add(c,slider,48)
            val labels=row(); labels.addView(text(s(R.string.include_weaker),11,Palette.muted),LinearLayout.LayoutParams(0,wrapContent,1f)); labels.addView(text(s(R.string.stronger_only),11,Palette.muted)); add(c,labels)
            space(c,18)
            val sound=Switch(this).apply { text=s(R.string.sound_title); textSize=14f; setTextColor(Palette.text); minHeight=dp(48); isChecked=model.sound
                setOnCheckedChangeListener { _, checked -> model.setSound(checked) } }; add(c,sound)
            add(c,text(s(R.string.sound_body),12,Palette.muted))
        }; space(body)
        card(body) { c ->
            add(c,text(s(R.string.export_title),17,bold=true)); space(c,8); add(c,text(s(R.string.export_body),13,Palette.muted)); space(c,14)
            add(c,button(s(R.string.export_csv)) { export() }); space(c,8)
            add(c,button(s(R.string.reset_session)) {
                AlertDialog.Builder(this).setTitle(R.string.reset_session).setMessage(R.string.reset_confirm)
                    .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.reset) { _,_->model.resetSession(); buildScreen() }.show()
            })
        }; space(body)
        card(body) { c ->
            add(c,text(s(R.string.local_title),17,bold=true)); space(c,8)
            add(c,text(s(R.string.local_body),13,Palette.muted)); space(c,12)
            add(c,button(s(R.string.privacy_policy)) { showPrivacyPolicy() }.apply { tag="privacy-policy" })
        }; space(body)
        card(body) { c -> add(c,text(s(R.string.limits_title),17,bold=true)); space(c,8); add(c,text(s(R.string.limits_body),13,Palette.muted)) }; space(body)
        add(body,button(s(R.string.radio_settings)) { radioSettings() }); space(body,10)
        add(body,button(s(if(model.demo.value) R.string.leave_demo else R.string.try_demo)) { changeMode() })
    }

    private fun showPrivacyPolicy() {
        val content = text(assets.open("privacy-policy.txt").bufferedReader(Charsets.UTF_8).use { it.readText() },14).apply {
            setPadding(dp(22),dp(12),dp(22),dp(22))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle(R.string.privacy_policy).setView(scroll)
            .setPositiveButton(R.string.close,null).show()
    }

    private fun render() {
        if(showingFinderKey!=model.finding.value?.device?.key) { buildScreen(); return }
        if(model.scanning.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model.finding.value?.let { updateFinder(it); return }
        val devices=model.devices.value
        countText?.text=s(R.string.signal_count,devices.size)
        stateText?.text=s(when { model.demo.value && model.scanning.value->R.string.demo_scanning
            model.scanning.value->R.string.live; model.sessionLog().isNotEmpty()->R.string.paused; else->R.string.ready })
        scanButton?.text=s(if(model.scanning.value) R.string.pause_short else R.string.scan_short)
        scanButton?.contentDescription=s(if(model.scanning.value) R.string.stop_scan else R.string.start_scan)
        radar?.running=model.scanning.value
        demoText?.visibility=if(model.demo.value) View.VISIBLE else View.GONE
        baselineBanner?.visibility=if(model.isRecordingBaseline.value) View.VISIBLE else View.GONE
        bleText?.text=s(R.string.channel_status,"BLE",status(model.bleStatus.value))
        wifiText?.text=s(R.string.channel_status,"Wi-Fi",status(model.wifiStatus.value))
        val attention=setOf(ChannelStatus.OFF,ChannelStatus.ERROR,ChannelStatus.UNAVAILABLE,ChannelStatus.THROTTLED)
        channelRow?.visibility=if(model.bleStatus.value in attention || model.wifiStatus.value in attention) View.VISIBLE else View.GONE
        baselineButton?.text=s(if(model.isRecordingBaseline.value) R.string.finish_baseline else R.string.record_baseline)
        baselineBody?.text=s(if(model.isRecordingBaseline.value) R.string.baseline_recording_body else R.string.baseline_body)
        rangeButton?.text=s(R.string.range_summary,rangeLabel(model.scanRange.value))
        stats?.let { r ->
            r.removeAllViews()
            val review=devices.count { !it.isBaseline && it.classification.risk in setOf(RiskLevel.HIGH,RiskLevel.MEDIUM) }
            listOf(Triple(devices.size,R.string.discovered,Palette.text),Triple(review,R.string.to_review,Palette.coral),
                Triple(devices.count { it.isBaseline },R.string.baseline_stat,Palette.mint)).forEach { (number,label,color) ->
                val stat=column().apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(12)) }
                stat.addView(text(number.toString(),24,color,true)); stat.addView(text(s(label),9,Palette.muted,true))
                r.addView(stat,LinearLayout.LayoutParams(0,wrapContent,1f))
            }
        }
        updateRows(); updateNavigation(); updateDetail()
        if(model.scanning.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun updateRows() {
        val container=results ?: return
        val list = if(tab==1) model.alertHistory.value else model.devices.value.filter { d ->
            val matches=when(filter) { 1->d.source==SignalSource.BLE; 2->d.source==SignalSource.WIFI
                3->!d.isBaseline && d.classification.risk in setOf(RiskLevel.HIGH,RiskLevel.MEDIUM)
                4->d.isBaseline; else->true }
            matches && (query.isBlank() || "${d.displayName} ${d.key} ${d.classification.label} ${d.classification.categoryId}".contains(query,true))
        }
        val fingerprint=listOf(list,tab,filter,query,model.scanning.value,model.permissionNeeded.value,model.scanRange.value)
        if(lastRows==fingerprint) return
        lastRows=fingerprint; container.removeAllViews()
        if(list.isEmpty()) {
            val title=when { tab==1->R.string.alerts_empty; query.isNotBlank() || filter!=0->R.string.no_matches
                model.scanRange.value!=null && model.sessionLog().isNotEmpty()->R.string.range_empty
                model.scanning.value->R.string.scan_wait; else->R.string.empty_title }
            val message=when(title) { R.string.alerts_empty->R.string.alerts_empty_body; R.string.no_matches->R.string.no_matches_body
                R.string.range_empty->R.string.range_empty_body
                R.string.scan_wait->R.string.scan_wait_body; else->R.string.empty_body }
            card(container) { c ->
                add(c,text(s(title),18,bold=true)); space(c,8); add(c,text(s(message),13,Palette.muted))
                if(title==R.string.range_empty) { space(c,12); add(c,button(s(R.string.range_change)) { showScanRange() }) }
                if(tab==0 && !model.scanning.value && query.isBlank() && filter==0) { space(c,12); add(c,button(s(R.string.try_demo)) { changeMode() }) }
                if(model.permissionNeeded.value) { space(c,8); add(c,text(s(R.string.permissions_denied),13,Palette.amber)); space(c,8); add(c,button(s(R.string.app_settings)) { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")) }) }
            }
        } else list.forEach { d -> deviceCard(container,d); space(container,10) }
    }
    private fun color(d: DetectedDevice): Int = if(d.isBaseline) Palette.mint else when(d.classification.risk) {
        RiskLevel.HIGH->Palette.coral; RiskLevel.MEDIUM->Palette.amber; RiskLevel.LOW->Palette.blue; RiskLevel.INFO->Palette.muted
    }
    private fun priority(d: DetectedDevice) = s(if(d.isBaseline) R.string.known_device else when(d.classification.risk) {
        RiskLevel.HIGH->R.string.high; RiskLevel.MEDIUM->R.string.medium; RiskLevel.LOW->R.string.low; RiskLevel.INFO->R.string.info
    })
    private fun deviceCard(parent: LinearLayout,d: DetectedDevice) {
        val c=column().apply { tag=d.key; background=rounded(Palette.surface,16,Palette.line); setPadding(dp(14),dp(16),dp(14),dp(16))
            isClickable=true; isFocusable=true; contentDescription=s(R.string.detail_description,d.displayName)
            setOnClickListener { showDevice(d) } }
        val top=row()
        val icon=GlyphView(this,d.classification.categoryId,color(d)).apply { background=rounded(0xFF1D2C37.toInt(),12); setPadding(dp(9),dp(9),dp(9),dp(9)) }
        top.addView(icon,LinearLayout.LayoutParams(dp(42),dp(42)))
        val names=column().apply { setPadding(dp(12),0,dp(8),0) }
        add(names,text(d.displayName,15,bold=true).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END })
        add(names,text(d.classification.label,11,Palette.muted).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        top.addView(names,LinearLayout.LayoutParams(0,wrapContent,1f))
        val signal=column().apply { gravity=Gravity.END }
        signal.addView(text(d.smoothedRssi.roundToInt().toString(),20,color(d),true))
        signal.addView(text("dBm",10,Palette.muted)); top.addView(signal)
        add(c,top); space(c,12)
        val meta=row(); meta.addView(pill(if(d.classification.requiresVerification) s(R.string.verification) else priority(d),color(d)))
        meta.addView(text(if(d.source==SignalSource.BLE) "BLE" else "Wi-Fi",10,Palette.muted).apply { gravity=Gravity.END },LinearLayout.LayoutParams(0,wrapContent,1f))
        add(c,meta); add(parent,c)
    }
    private fun status(value: ChannelStatus) = s(when(value) {
        ChannelStatus.IDLE->R.string.status_idle; ChannelStatus.SCANNING->R.string.status_scanning
        ChannelStatus.WAITING->R.string.status_waiting; ChannelStatus.THROTTLED->R.string.status_throttled
        ChannelStatus.OFF->R.string.status_off; ChannelStatus.UNAVAILABLE->R.string.status_unavailable; ChannelStatus.ERROR->R.string.status_error
    })
    private fun rangeLabel(minimumRssi: Int?): String = when(minimumRssi) {
        null -> s(R.string.range_all)
        ScanRange.NEARBY -> s(R.string.range_preset_value,s(R.string.range_nearby),minimumRssi)
        ScanRange.BALANCED -> s(R.string.range_preset_value,s(R.string.range_balanced),minimumRssi)
        ScanRange.WIDE -> s(R.string.range_preset_value,s(R.string.range_wide),minimumRssi)
        else -> s(R.string.range_preset_value,s(R.string.range_custom),minimumRssi)
    }
    private fun showScanRange() {
        var selected=model.scanRange.value
        val scroll=ScrollView(this)
        val content=column().apply { setPadding(dp(22),dp(8),dp(22),dp(16)) }
        scroll.addView(content)
        add(content,text(s(R.string.range_body),13,Palette.muted)); space(content,12)
        val choices=RadioGroup(this).apply { orientation=RadioGroup.VERTICAL }
        val presets=listOf<Int?>(null,ScanRange.NEARBY,ScanRange.BALANCED,ScanRange.WIDE)
        val ids=(0..4).map { View.generateViewId() }
        val initial=presets.indexOf(selected).takeIf { it>=0 } ?: 4
        val slider=SeekBar(this).apply {
            max=ScanRange.MAX_RSSI-ScanRange.MIN_RSSI
            progress=(selected ?: ScanRange.BALANCED)-ScanRange.MIN_RSSI
            contentDescription=s(R.string.range_custom); tag="range-slider"
            progressTintList=ColorStateList.valueOf(Palette.mint); thumbTintList=ColorStateList.valueOf(Palette.mint)
        }
        val value=text("",22,Palette.mint,true)
        fun refreshValue() {
            value.text=selected?.let { s(R.string.range_minimum,it) } ?: s(R.string.range_all)
            slider.isEnabled=selected!=null
        }
        (0..4).forEach { index ->
            choices.addView(RadioButton(this).apply {
                id=ids[index]; text=if(index<4) rangeLabel(presets[index]) else s(R.string.range_custom)
                textSize=14f; setTextColor(Palette.text); buttonTintList=ColorStateList.valueOf(Palette.mint)
                minHeight=dp(48); tag="range-option-$index"
            },RadioGroup.LayoutParams(matchParent,wrapContent))
        }
        choices.check(ids[initial])
        choices.setOnCheckedChangeListener { _, checkedId ->
            val index=ids.indexOf(checkedId)
            selected=if(index in 0..3) presets[index] else slider.progress+ScanRange.MIN_RSSI
            selected?.let { slider.progress=it-ScanRange.MIN_RSSI }
            refreshValue()
        }
        slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser) {
                    selected=progress+ScanRange.MIN_RSSI
                    choices.check(ids[4]); refreshValue()
                }
            }
            override fun onStartTrackingTouch(seekBar:SeekBar?) {}
            override fun onStopTrackingTouch(seekBar:SeekBar?) {}
        })
        add(content,choices); space(content,8); add(content,value); add(content,slider,48)
        val endpoints=row()
        endpoints.addView(text(s(R.string.range_wider),11,Palette.muted),LinearLayout.LayoutParams(0,wrapContent,1f))
        endpoints.addView(text(s(R.string.range_closer),11,Palette.muted)); add(content,endpoints)
        space(content,12); add(content,text(s(R.string.range_applies),12,Palette.muted))
        refreshValue()
        AlertDialog.Builder(this).setTitle(R.string.range_title).setView(scroll)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.range_apply) { _,_-> model.setScanRange(selected); render() }.show()
    }
    private fun toggleScan() {
        if(model.scanning.value) model.stop()
        else if(model.demo.value || ScanPermissions.allGranted(this)) {
            model.start()
            if(!model.scanning.value) radioSettings()
        } else AlertDialog.Builder(this).setTitle(R.string.permission_title).setMessage(R.string.permission_body)
            .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.allow_permissions) { _,_-> requestPermissions(ScanPermissions.required(),41) }.show()
    }
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==41) {
            model.start()
            if(!model.scanning.value && ScanPermissions.allGranted(this)) radioSettings()
        }
    }
    private fun radioSettings() {
        AlertDialog.Builder(this).setTitle(R.string.radio_settings).setMessage(R.string.setup_body)
            .setItems(arrayOf(s(R.string.bluetooth_settings),s(R.string.wifi_settings),s(R.string.location_settings),s(R.string.app_settings))) { _,index ->
                when(index) { 0->openSettings(Settings.ACTION_BLUETOOTH_SETTINGS); 1->openSettings(Settings.ACTION_WIFI_SETTINGS)
                    2->openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS); else->openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")) }
            }.setNegativeButton(R.string.close,null).show()
    }
    private fun openSettings(action:String,data:Uri?=null) { runCatching { startActivity(Intent(action,data)) } }
    private fun changeMode() {
        val change={ if(model.demo.value) model.leaveDemo() else model.startDemo(); buildScreen() }
        if(model.sessionLog().isEmpty()) change()
        else AlertDialog.Builder(this).setMessage(R.string.demo_confirm).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(if(model.demo.value) R.string.leave_demo else R.string.start_demo) { _,_->change() }.show()
    }
    private fun info() { AlertDialog.Builder(this).setTitle(R.string.local_title).setMessage(R.string.local_body).setPositiveButton(R.string.done,null).show() }

    private fun showDevice(device:DetectedDevice) {
        selectedKey=device.key
        lateinit var dialog: AlertDialog
        val scroll=ScrollView(this)
        val c=column().apply { setPadding(dp(22),dp(12),dp(22),dp(22)) }; scroll.addView(c)
        add(c,pill(if(model.demo.value) s(R.string.demo) else priority(device),color(device))); space(c,14)
        add(c,text(device.classification.label,16,bold=true)); space(c,12)
        add(c,button(s(R.string.find_device),true) {
            dialog.dismiss()
            model.findDevice(device.key)
            if(!model.scanning.value) toggleScan()
        }.apply { tag="find-device" }); space(c,12)
        if(device.classification.requiresVerification) { add(c,text(s(R.string.unverified_note),13,Palette.amber)); space(c,12) }
        add(c,text(s(R.string.signal_strength),10,Palette.muted,true))
        detailStrength=text(s(R.string.signal_value,device.smoothedRssi.roundToInt()),32,Palette.mint,true).also { add(c,it) }
        detailChart=SignalChart(this).apply { points=device.rssiHistory }.also { add(c,it,75) }
        add(c,text(s(R.string.signal_note),12,Palette.muted)); space(c,18)
        fun value(label:Int,value:String) { add(c,text(s(label).uppercase(),10,Palette.muted,true)); add(c,text(value,13).apply { setTextIsSelectable(true) }); space(c,12) }
        value(R.string.address,device.key.substringAfter(':')); value(R.string.source,device.source.name)
        value(R.string.matched_rule,device.classification.ruleId ?: s(R.string.no_rule))
        detailSamples=text(s(R.string.observations_value,device.seenCount),13).also { add(c,it) }; space(c,10)
        value(R.string.first_seen,SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(device.firstSeenMs)))
        detailLast=text("",12,Palette.muted).also { add(c,it) }; space(c,12)
        device.frequencyMhz?.let { value(R.string.frequency,"$it MHz") }
        if(device.manufacturerIds.isNotEmpty()) value(R.string.manufacturers,device.manufacturerIds.joinToString { "0x%04X".format(it) })
        if(device.serviceUuids.isNotEmpty()) value(R.string.services,device.serviceUuids.joinToString("\n"))
        add(c,text(s(R.string.identification_note),12,Palette.muted)); space(c,18)
        dialog=AlertDialog.Builder(this).setTitle(device.displayName).setView(scroll).setNegativeButton(R.string.close,null).create()
        add(c,button(s(if(device.isBaseline) R.string.remove_baseline else R.string.add_baseline),true) {
            if(device.isBaseline) model.removeBaseline(device) else model.whitelist(device)
            dialog.dismiss(); render()
        })
        dialog.setOnDismissListener { selectedKey=null; detailStrength=null; detailChart=null; detailLast=null; detailSamples=null }
        dialog.show(); updateDetail()
    }
    private fun updateDetail() {
        val key=selectedKey ?: return
        val d=model.sessionLog().find { it.key==key } ?: return
        detailStrength?.text=s(R.string.signal_value,d.smoothedRssi.roundToInt())
        detailChart?.points=d.rssiHistory
        detailLast?.text=s(R.string.last_seen_value,((System.currentTimeMillis()-d.lastSeenMs)/1000).coerceAtLeast(0))
        detailSamples?.text=s(R.string.observations_value,d.seenCount)
    }
    private fun export() {
        if(model.sessionLog().isEmpty()) { Toast.makeText(this,s(R.string.nothing_export),Toast.LENGTH_LONG).show(); return }
        pendingExport=SessionCsv.encode(model.sessionLog(),model.demo.value,model.scanRange.value)
        val name="iot-scan-${if(model.demo.value) "demo-" else ""}${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(Date())}.csv"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="text/csv"; putExtra(Intent.EXTRA_TITLE,name) },42)
    }
    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=42) return
        val csv=pendingExport; pendingExport=null
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null || csv==null) return
        uiScope.launch {
            val success=withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) } ?: error("No document stream") }.isSuccess }
            Toast.makeText(this@MainActivity,s(if(success) R.string.export_saved else R.string.export_error),Toast.LENGTH_LONG).show()
        }
    }
}
