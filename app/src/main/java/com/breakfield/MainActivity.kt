package com.breakfield

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.text.TextUtils
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.Context
import com.breakfield.audio.Engine
import com.breakfield.audio.RATE
import com.breakfield.model.Clip
import com.breakfield.model.WavReader
import com.breakfield.ui.WaveformView
import com.breakfield.ui.PocketTextView
import com.breakfield.ui.PocketButton
import java.util.Locale
import kotlin.math.roundToInt

/** Single-screen native Android touch UI, built without Compose or third-party dependencies.
 * No external assets are required: drum synth, bass synth and click work out of the box.
 */
class MainActivity : Activity() {
    private val engine = Engine()
    private val accent = Color.rgb(45, 204, 180)
    private val ink = Color.rgb(237, 244, 247)
    private val muted = Color.rgb(160, 177, 190)
    private val panel = Color.rgb(29, 37, 48)
    private val background = Color.rgb(17, 22, 30)
    private var tab = 0
    // 0 = follow device, 1 = portrait lock, 2 = landscape lock.
    private var orientationChoice = 0
    private val modeButtons = mutableListOf<Button>()
    private val orientationPrefs by lazy { getSharedPreferences("breakfield_interface", Context.MODE_PRIVATE) }
    private fun isPortraitUi(): Boolean = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var transport: Button
    private var requestedDeviceId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val tabs = arrayOf("LOOPS", "BOW", "BEATS", "BASS", "RECORD", "SETTINGS")
    private val refresh = object: Runnable {
        override fun run() {
            if (::status.isInitialized) {
                val state = engine.recordState.name.replace('_',' ')
                val routed = if (engine.input.isActive) engine.input.routedInput else "Input off"
                val clipInfo = "${engine.clips.size} loops • ${engine.clips.sumOf { it.slices.size }} slices"
                status.text = if (isPortraitUi())
                    "${engine.tempo.roundToInt()} BPM  •  $clipInfo  •  $state"
                else
                    "${if (engine.playing) "▶" else "■"}  ${engine.tempo.roundToInt()} BPM  ·  $clipInfo  ·  $state  ·  $routed"
            }
            if (::transport.isInitialized) transport.text = if (engine.playing) "PAUSE" else "PLAY"
            handler.postDelayed(this,250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientationChoice = orientationPrefs.getInt("orientation_mode", 0).coerceIn(0, 2)
        tab = savedInstanceState?.getInt("active_tab", 0)?.coerceIn(0, tabs.lastIndex) ?: 0
        applyRequestedOrientation()
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0
        buildShell()
        engine.start()
        handler.post(refresh)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("active_tab", tab)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reflow the existing player; the engine and audio buffers are not restarted.
        buildShell()
    }
    private fun applyRequestedOrientation() {
        val wanted = when (orientationChoice) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }
    private fun changeOrientation(choice: Int) {
        orientationChoice = choice.coerceIn(0, 2)
        orientationPrefs.edit().putInt("orientation_mode", orientationChoice).apply()
        applyRequestedOrientation()
        buildShell()
    }
    override fun onStop() {
        // Avoid capturing audio in the background without a microphone foreground service.
        engine.input.stop()
        super.onStop()
    }
    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        engine.stop()
        super.onDestroy()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun pad(view: View, all: Int = 12) { view.setPadding(dp(all),dp(all),dp(all),dp(all)) }
    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(this).apply {
        this.text = value
        this.textSize = size
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(),1f)
    }
    private fun button(label: String, onClick: () -> Unit) = PocketButton(this).apply {
        text = label
        textSize = 11f
        setAllCaps(false)
        usePixelInk(ink)
        backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        setOnClickListener { onClick() }
        minimumHeight = dp(42)
        minHeight = dp(42)
    }
    private fun section(title: String) {
        val heading = PocketTextView(this).apply { text = title.uppercase(Locale.US); textSize = 16f; setTextColor(accent) }
        heading.setPadding(0, dp(if (isPortraitUi()) 12 else 18),0,dp(8))
        content.addView(heading)
    }
    private fun help(value: String) {
        val v = text(value, 12f, muted)
        v.setPadding(0,dp(4),0,dp(8))
        content.addView(v)
    }
    private fun row(vararg views: View) {
        val line = horizontal()
        for (v in views) line.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        content.addView(line)
    }
    private fun adaptiveFour(vararg views: View) {
        if (isPortraitUi() && views.size == 4) {
            row(views[0], views[1]); row(views[2], views[3])
        } else row(*views)
    }
    private fun toggle(label: String, checked: Boolean, callback: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            text = label
            textSize = 13f
            setTextColor(ink)
            isChecked = checked
            setPadding(0,dp(6),0,dp(6))
            setOnCheckedChangeListener { _, value -> callback(value) }
        })
    }
    private fun slider(label: String, value: Float, min: Float, max: Float, format: String="%.0f", onChange: (Float) -> Unit) {
        val labelText = text("$label   ${String.format(Locale.US,format,value)}",13f)
        content.addView(labelText)
        val seek = SeekBar(this)
        seek.max = 1000
        seek.progress = (((value-min)/(max-min))*1000).roundToInt().coerceIn(0,1000)
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(v: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newValue = min + (max-min)*p/1000f
                labelText.text = "$label   ${String.format(Locale.US,format,newValue)}"
                onChange(newValue)
            }
            override fun onStartTrackingTouch(v: SeekBar?) {}
            override fun onStopTrackingTouch(v: SeekBar?) {}
        })
        content.addView(seek)
    }
    private fun select(label: String, options: List<String>, selected: Int, callback: (Int) -> Unit) {
        content.addView(text(label,13f,muted))
        val menu = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        menu.adapter = adapter
        if (options.isNotEmpty()) menu.setSelection(selected.coerceIn(0,options.lastIndex))
        var first = true
        menu.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (first) { first=false; return }
                callback(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        content.addView(menu)
    }
    private fun makeTempoBar(): LinearLayout {
        val tempoBar = horizontal()
        val tempoLabel = PocketTextView(this).apply {
            text = "${engine.tempo.roundToInt()} BPM"
            textSize = 13f
            setTextColor(accent)
        }
        tempoBar.addView(tempoLabel, LinearLayout.LayoutParams(dp(78), dp(40)))
        val tempoSeek = SeekBar(this).apply {
            max = 260
            progress = (engine.tempo - 40.0).roundToInt().coerceIn(0,260)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(v: SeekBar?, p: Int, user: Boolean) {
                    if (user) {
                        engine.tempo = (40+p).toDouble()
                        tempoLabel.text = "${40+p} BPM"
                    }
                }
                override fun onStartTrackingTouch(v: SeekBar?) {}
                override fun onStopTrackingTouch(v: SeekBar?) {}
            })
        }
        tempoBar.addView(tempoSeek, LinearLayout.LayoutParams(0,dp(42),1f))
        return tempoBar
    }
    private fun modeButton(index: Int): Button = button(tabs[index]) {
        tab = index
        showTab()
    }.also { b -> modeButtons.add(b) }

    private fun recolorModes() {
        modeButtons.forEachIndexed { index, b ->
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (tab == index) accent else panel)
            (b as? PocketButton)?.usePixelInk(if (tab == index) background else ink)
            b.contentDescription = "${tabs[index]}${if (tab == index) ", selected" else ""}"
        }
    }
    private fun buildShell() {
        engine.releaseAllTouches()
        modeButtons.clear()
        val portrait = isPortraitUi()
        root = vertical().apply {
            setBackgroundColor(this@MainActivity.background)
            pad(this, if (portrait) 6 else 10)
        }

        // Transport stays pinned on both orientations so recording remains one tap away.
        val header = horizontal()
        val title = PocketTextView(this).apply {
            text = "BREAKFIELD"
            textSize = if (portrait) 21f else 24f
            setTextColor(ink)
        }
        header.addView(title, LinearLayout.LayoutParams(0,dp(45),1f))
        header.addView(button("ROTATE") {
            // A manual rotation locks that orientation; SETTINGS can restore Auto.
            changeOrientation(if (isPortraitUi()) 2 else 1)
        }, LinearLayout.LayoutParams(dp(if (portrait) 82 else 105),dp(44)))
        if (!portrait) {
            transport = button("PLAY") { engine.toggleTransport() }
            header.addView(transport, LinearLayout.LayoutParams(dp(104),dp(44)))
            header.addView(button("CLICK") {
                engine.click = !engine.click
                toast(if (engine.click) "Metronome on" else "Metronome off")
            }, LinearLayout.LayoutParams(dp(100),dp(44)))
        }
        root.addView(header)
        if (portrait) {
            val transportLine = horizontal()
            transport = button("PLAY") { engine.toggleTransport() }
            transportLine.addView(transport,LinearLayout.LayoutParams(0,dp(48),1f))
            transportLine.addView(button("CLICK") {
                engine.click = !engine.click
                toast(if (engine.click) "Metronome on" else "Metronome off")
            },LinearLayout.LayoutParams(0,dp(48),1f))
            root.addView(transportLine)
        }
        root.addView(makeTempoBar())

        status = text("Ready",11f,muted).apply {
            maxLines=1
            ellipsize=TextUtils.TruncateAt.END
            setPadding(dp(6),dp(5),dp(6),dp(5))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            clipToPadding = false
        }
        content = vertical().apply { pad(this,if (portrait) 6 else 10) }
        scroll.addView(content)
        if (portrait) {
            // Primary gesture surface and detailed controls scroll, while a 2x3
            // thumb-reachable navigation dock stays fixed above system navigation.
            root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
            root.addView(status)
            val dock = vertical().apply { setBackgroundColor(panel) }
            for (r in 0..1) {
                val line = horizontal()
                for (c in 0..2) {
                    val b=modeButton(r*3+c)
                    line.addView(b,LinearLayout.LayoutParams(0,dp(50),1f))
                }
                dock.addView(line)
            }
            root.addView(dock)
        } else {
            // Landscape uses a narrow mode rail, freeing vertical space for the
            // instrument and leaving the waveform as the dominant control.
            val workspace = horizontal()
            val railScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                setBackgroundColor(panel)
            }
            val rail = vertical()
            for (i in tabs.indices) {
                rail.addView(modeButton(i),LinearLayout.LayoutParams(dp(112),dp(50)))
            }
            railScroll.addView(rail)
            workspace.addView(railScroll,LinearLayout.LayoutParams(dp(112),ViewGroup.LayoutParams.MATCH_PARENT))
            workspace.addView(scroll,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f))
            root.addView(workspace,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
            root.addView(status)
        }
        setContentView(root)
        showTab()
    }
    private fun showTab() {
        recolorModes()
        engine.releaseAllTouches()
        content.removeAllViews()
        when (tab) {
            0 -> showLoops()
            1 -> showBow()
            2 -> showBeats()
            3 -> showBass()
            4 -> showRecord()
            else -> showSettings()
        }
    }
    private fun clipSelector() {
        val names = if (engine.clips.isEmpty()) listOf("No loops loaded") else engine.clips.mapIndexed { index, c -> "${index+1}: ${c.name} (${c.slices.size} slices)" }
        select("Active loop",names,engine.selectedClip) { engine.selectedClip=it; showTab() }
    }
    private fun waveform(mode: Boolean = false) {
        val display = WaveformView(this,engine)
        display.editSlices = mode
        val displayHeight = if (isPortraitUi())
            (resources.configuration.screenHeightDp * 0.33f).roundToInt().coerceIn(195,300)
        else 180
        content.addView(display,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(displayHeight)))
    }
    private fun showLoops() {
        section("Sample library / slice editor")
        row(button("IMPORT WAV") { importWav() },button("AUTO SLICE") {
            engine.clips.getOrNull(engine.selectedClip)?.autoSlice(); showTab()
        })
        clipSelector()
        help("Multiple 16-bit PCM or 32-bit float WAV files. Mono and stereo imports become 48 kHz mono playback clips. Slice tags are heuristic and editable.")
        waveform(true)
        val clip = engine.clips.getOrNull(engine.selectedClip)
        if (clip != null) {
            help("Tap the waveform to split a slice. Select a slice to edit its tag:")
            val labels = clip.slices.mapIndexed { index, s -> "${index+1} • ${s.tag.display} • ${"%.2f".format(Locale.US,s.start/RATE.toDouble())}s" }
            if (labels.isNotEmpty()) {
                select("Slices",labels,0) { selectedSlice = it }
                select("Selected slice tag",com.breakfield.model.Tag.entries.map { it.display },
                    clip.slices.getOrNull(selectedSlice)?.tag?.ordinal ?: 0) { tag ->
                    clip.slices.getOrNull(selectedSlice)?.tag = com.breakfield.model.Tag.entries[tag]
                }
            }
        }
        section("Clip behaviour")
        help("Playback and Grids routing currently use the imported slice positions directly. Independent time-stretch algorithms and project persistence are planned for the next milestones.")
        row(button("PLAY SAMPLE") {
            engine.selectedClip=engine.selectedClip.coerceAtLeast(0)
            engine.triggerTouch(999,0f,0.5f)
            handler.postDelayed({ engine.releaseTouch(999) },900)
        },button("REMOVE LOOP") {
            if (engine.clips.isNotEmpty()) { engine.clips.removeAt(engine.selectedClip.coerceIn(0,engine.clips.lastIndex)); engine.selectedClip=0; showTab() }
        })
    }
    private var selectedSlice=0
    private fun showBow() {
        section("Granular E-Bow / microlooping")
        clipSelector()
        waveform()
        help("X: start position. Y: loop length (top is longer). Touch and hold to sustain. Multiple fingers create independent voices. Headphones recommended.")
        toggle("Granular cloud (off = continuous microloop)",engine.granular) { engine.granular=it }
        slider("Microfade / seam crossfade (ms)", engine.microfadeMs,0f,100f,"%.1f") { engine.microfadeMs=it }
        slider("Grain size (ms)",engine.grainSizeMs,5f,450f,"%.0f") { engine.grainSizeMs=it }
        slider("Grain density (grains/s)",engine.grainDensity,1f,160f,"%.0f") { engine.grainDensity=it }
        slider("Grain start randomness (%)",engine.startRandom*100,0f,100f) { engine.startRandom=it/100 }
        slider("Grain size randomness (%)",engine.sizeRandom*100,0f,100f) { engine.sizeRandom=it/100 }
        slider("Density timing randomness (%)",engine.densityRandom*100,0f,100f) { engine.densityRandom=it/100 }
        help("0 ms allows hard cuts and clicks. Increasing microfade shapes grain edges and smooths loop seams. The continuous-loop and granular renderers share this parameter.")
    }
    private fun showBeats() {
        section("Grids-inspired rhythmic generator")
        help("Three rhythmic lanes (kick / snare / hat) morph through a small original pattern map. This is not a port of Mutable Instruments Grids firmware.")
        toggle("Generate drums",engine.drumsEnabled) { engine.drumsEnabled=it }
        slider("Map X",engine.gridsX*100,0f,100f) { engine.gridsX=it/100 }
        slider("Map Y",engine.gridsY*100,0f,100f) { engine.gridsY=it/100 }
        slider("Drum trigger density (%)",engine.drumProbability*100,0f,100f) { engine.drumProbability=it/100 }
        slider("Kick probability (%)",engine.kickProbability*100,0f,100f) { engine.kickProbability=it/100 }
        slider("Snare probability (%)",engine.snareProbability*100,0f,100f) { engine.snareProbability=it/100 }
        slider("Hat probability (%)",engine.hatProbability*100,0f,100f) { engine.hatProbability=it/100 }
        slider("Bass slice probability (%)",engine.sliceBassProbability*100,0f,100f) { engine.sliceBassProbability=it/100 }
        slider("Melodic slice probability (%)",engine.melodicProbability*100,0f,100f) { engine.melodicProbability=it/100 }
        select("Slice voice policy",listOf("Monophonic (all choke)","Choke by tag", "Polyphonic"),engine.chokeMode) { engine.chokeMode=it }
        slider("Chaos (%)",engine.chaos*100,0f,100f) { engine.chaos=it/100 }
        slider("Swing (%)",engine.swing*100,0f,90f) { engine.swing=it/100 }
        help("Generated events prefer matching tagged slices from loaded loops, falling back to the built-in synth drum voices.")
    }
    private fun showBass() {
        section("Generative bassline")
        toggle("Bass generator enabled",engine.bassEnabled) { engine.bassEnabled=it }
        adaptiveFour(*Array(4) { i -> button("SLOT ${'A'+i}") { engine.loadBassSlot(i); showTab() } })
        row(button("SAVE CURRENT SLOT") { engine.saveBassSlot(engine.bassSlot); toast("Saved bass slot ${'A'+engine.bassSlot}") },
            button("NEW VARIATION") { engine.saveBassSlot(engine.bassSlot); toast("New variation seed") })
        select("Scale",listOf("Natural minor", "Major", "Dorian", "Phrygian", "Minor pentatonic"),engine.scale) { engine.scale=it }
        select("Root",listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"),((engine.bassRoot % 12)+12)%12) {
            engine.bassRoot=36+it
        }
        toggle("Bass follows drum Grids map",engine.bassFollowDrums) { engine.bassFollowDrums=it }
        slider("Bass Grids X",engine.bassGridsX*100,0f,100f) { engine.bassGridsX=it/100 }
        slider("Bass Grids Y",engine.bassGridsY*100,0f,100f) { engine.bassGridsY=it/100 }
        slider("Bass probability (%)",engine.bassProbability*100,0f,100f) { engine.bassProbability=it/100 }
        slider("Warp (%)",engine.warp*100,0f,100f) { engine.warp=it/100 }
        slider("Shift (16 steps)",engine.shift.toFloat(),-8f,8f) { engine.shift=it.roundToInt() }
        help("The bassline uses the shared tempo grid, scale-aware note selection, controlled randomness, four variation memories and a built-in mono synthesizer.")
    }
    private fun showRecord() {
        section("Live looping / overdub")
        help("Four independently replayable live tracks. Record from the phone microphone or Android-supported USB audio. Use headphones to avoid feedback.")
        adaptiveFour(*Array(4) { i -> button("TRACK ${i+1}${if (engine.selectedLiveTrack==i) " •" else ""}") {
            if (engine.recordState == Engine.RecordState.IDLE) { engine.selectedLiveTrack=i; showTab() }
            else toast("Finish the current recording before switching tracks")
        } })
        row(button("MUTE / UNMUTE TRACK") { engine.toggleTrackMute(engine.selectedLiveTrack); showTab() },
            button("STOP ALL LOOPS") { engine.loopPlaying=!engine.loopPlaying })
        help("Track ${engine.selectedLiveTrack+1} is ${if (engine.liveTracks[engine.selectedLiveTrack].mute) "muted" else "audible"}. Each live track has its own recording and overdub history.")
        val inputs=engine.input.availableInputs(this)
        val inputLabels=inputs.map { "${it.productName}  [${typeName(it)}]" }
        val displayInputs=if (inputLabels.isEmpty()) listOf("No recording inputs detected") else inputLabels
        select("Recording source",displayInputs,inputs.indexOfFirst { it.id==requestedDeviceId }.coerceAtLeast(0)) { i ->
            requestedDeviceId=inputs.getOrNull(i)?.id ?: -1
        }
        row(button(if (engine.input.isActive) "STOP INPUT" else "START INPUT") {
            if (engine.input.isActive) { engine.input.stop(); showTab() }
            else ensureRecordingPermission()
        },button("REFRESH INPUTS") { showTab() })
        help("Input route: ${engine.input.routedInput}. Device selection requests a preferred route, which Android may override.")
        select("Record length",listOf("Free (tap to stop)","Fixed (quantised)"),if (engine.recordMode==Engine.RecordMode.FREE) 0 else 1) {
            engine.recordMode=if (it==0) Engine.RecordMode.FREE else Engine.RecordMode.FIXED
        }
        val lengths = listOf("1 beat", "2 beats", "1 bar", "2 bars", "4 bars", "8 bars", "16 bars")
        val beats = listOf(1.0,2.0,4.0,8.0,16.0,32.0,64.0)
        select("Fixed loop length",lengths,beats.indexOf(engine.recordBeats).coerceAtLeast(3)) { engine.recordBeats=beats[it] }
        val count = listOf(0,1,2,4)
        select("Count-in",listOf("Off", "1 bar", "2 bars", "4 bars"),count.indexOf(engine.countInBars).coerceAtLeast(1)) { engine.countInBars=count[it] }
        row(button("RECORD / STOP") {
            if (!engine.input.isActive) { toast("Start a microphone or USB input first") }
            else engine.armRecording()
        },button("OVERDUB ON/OFF") {
            if (!engine.input.isActive) toast("Start an input first")
            else engine.toggleOverdub()
        })
        row(button("UNDO OVERDUB") { engine.undoOverdub() },button("CLEAR LOOP") { engine.clearLiveLoop() })
        row(button("SEND TO SLICER") {
            engine.snapshotLiveLoop()?.let { engine.clips.add(Clip("Live take ${engine.clips.size+1}",it)); engine.selectedClip=engine.clips.lastIndex; tab=0; showTab() }
                ?: toast("Record a loop first")
        },button("PLAY / MUTE LOOP") { engine.loopPlaying=!engine.loopPlaying })
        toggle("Live-loop playback",engine.looperEnabled) { engine.looperEnabled=it }
        toggle("Input monitor (headphones only)",engine.inputMonitor) { engine.inputMonitor=it }
        slider("Overdub feedback (%)",engine.overdubFeedback*100,0f,100f) { engine.overdubFeedback=it/100 }
        slider("Loop microfade (ms)",engine.microfadeMs,0f,100f,"%.1f") { engine.microfadeMs=it }
        help("Record state: ${engine.recordState}. Current loop: ${"%.2f".format(Locale.US,engine.liveLoopLength/RATE.toDouble())} seconds. A free loop keeps its original timing; fixed loops automatically stop after their selected duration.")
    }
    private fun showSettings() {
        section("Interface / orientation")
        select("Screen orientation",listOf("Auto (follow device)","Portrait (locked)","Landscape (locked)"),orientationChoice) {
            changeOrientation(it)
        }
        help("Auto follows your phone rotation. Portrait uses a bottom mode dock, a full-width touch waveform and stacked editing controls. Landscape uses a vertical mode rail. The audio engine keeps running during rotation.")
        section("Audio and performance")
        slider("Master volume (%)",engine.masterVolume*100,0f,100f) { engine.masterVolume=it/100 }
        slider("Metronome volume (%)",engine.clickVolume*100,0f,60f) { engine.clickVolume=it/100 }
        toggle("Audible metronome",engine.click) { engine.click=it }
        toggle("Drum generation",engine.drumsEnabled) { engine.drumsEnabled=it }
        toggle("Bass generation",engine.bassEnabled) { engine.bassEnabled=it }
        section("This build")
        help("BREAKFIELD 0.1 alpha • Android 8.0+ • 48 kHz float stereo output, mono input • multiple WAV imports • slice tagging • Grids-inspired drum generation • scale bass • live free/fixed capture and overdub • granular touch surface.")
        help("Not yet implemented: phase-vocoder / WSOLA stretching, full non-destructive edit history, project persistence, MIDI, native C++ Oboe backend and export/render. Android device audio latency varies.")
    }
    private fun typeName(device: AudioDeviceInfo): String = when(device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
        AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "Input ${device.type}"
    }
    private fun ensureRecordingPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSelectedInput()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),910)
    }
    private fun startSelectedInput() {
        val inputs = engine.input.availableInputs(this)
        val preferred = inputs.firstOrNull { it.id==requestedDeviceId } ?: inputs.firstOrNull()
        if (!engine.input.start(this,preferred)) toast("Input error: ${engine.input.lastError}")
        else toast("Recording from: ${engine.input.routedInput}")
        showTab()
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode==910) {
            if (grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) startSelectedInput()
            else toast("Recording requires microphone permission")
        }
    }
    private fun importWav() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        }
        startActivityForResult(intent,911)
    }
    @Deprecated("Uses the platform activity result API to avoid third-party dependencies")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 911 || resultCode != RESULT_OK || data==null) return
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { clips -> for (i in 0 until clips.itemCount) uris.add(clips.getItemAt(i).uri) }
        data.data?.let { if (!uris.contains(it)) uris.add(it) }
        var loaded=0
        for (uri in uris) {
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported loop"
                contentResolver.openInputStream(uri)?.use { input ->
                    engine.clips.add(WavReader.read(input,name))
                    loaded++
                }
            } catch (error: Exception) { toast("Could not load: ${error.message}") }
        }
        if (loaded>0) { engine.selectedClip=engine.clips.lastIndex; toast("Loaded $loaded loop(s)"); showTab() }
    }
    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
}
