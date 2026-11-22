package com.breakfield.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.breakfield.model.Clip
import com.breakfield.model.Tag
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlin.random.Random

const val RATE = 48000
private const val TWO_PI = Math.PI * 2.0

/** Audio engine owned by the Activity. 48 kHz float stereo, streamed without external dependencies.
 * The event clock is derived from rendered frames and never from UI timers.
 */
class Engine {
    @Volatile var tempo = 120.0
    @Volatile var playing = false
    @Volatile var click = true
    @Volatile var clickVolume = 0.16f
    @Volatile var swing = 0f
    @Volatile var microfadeMs = 8f
    @Volatile var grainSizeMs = 80f
    @Volatile var grainDensity = 28f
    @Volatile var startRandom = 0.12f
    @Volatile var sizeRandom = 0.10f
    @Volatile var densityRandom = 0.12f
    @Volatile var granular = true
    @Volatile var masterVolume = 0.7f
    @Volatile var inputMonitor = false
    @Volatile var drumProbability = 0.75f
    @Volatile var kickProbability = 1f
    @Volatile var snareProbability = 1f
    @Volatile var hatProbability = 1f
    @Volatile var sliceBassProbability = .45f
    @Volatile var melodicProbability = .25f
    @Volatile var chokeMode = 2 // 0 mono, 1 per tag, 2 poly
    @Volatile var bassProbability = 0.6f
    @Volatile var gridsX = 0.45f
    @Volatile var gridsY = 0.45f
    @Volatile var bassGridsX = 0.45f
    @Volatile var bassGridsY = 0.45f
    @Volatile var bassFollowDrums = true
    @Volatile var chaos = 0.08f
    @Volatile var bassRoot = 36 // C2
    @Volatile var scale = 0
    @Volatile var warp = 0.15f
    @Volatile var shift = 0
    @Volatile var bassSlot = 0
    @Volatile var drumsEnabled = true
    @Volatile var bassEnabled = true
    @Volatile var looperEnabled = true
    @Volatile var selectedClip = 0
    @Volatile var beatPosition = 0.0
    @Volatile var stepPosition = 0
    @Volatile var recordState = RecordState.IDLE
    @Volatile var recordMode = RecordMode.FIXED
    @Volatile var recordBeats = 8.0
    @Volatile var countInBars = 1
    @Volatile var overdubFeedback = 1f
    @Volatile var selectedLiveTrack = 0
    val liveTracks = Array(4) { LiveTrack() }
    val liveLoopLength: Int get() = liveTracks[selectedLiveTrack.coerceIn(0,3)].pcm.size
    @Volatile var loopPlaying = true

    val clips = CopyOnWriteArrayList<Clip>()
    val input = InputCapture()
    private val running = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private val grains = ArrayList<Grain>()
    private val shots = ArrayList<Shot>()
    private val touches = LinkedHashMap<Int, TouchVoice>()
    private val touchLock = Any()
    @Volatile private var touchSnapshot: Array<TouchVoice> = emptyArray()
    private var sampleClock = 0L
    private var nextStep = 0.0
    private var nextClick = 0.0
    private var clickPhase = -1
    private var clickAccent = false
    private var nextCountIn = 0.0
    private var countInRemaining = 0
    private var bassPhase = 0.0
    private var bassFrequency = 0.0
    private var bassEnvelope = 0.0
    private var bassAccent = 0.7
    private var noiseSeed = 0x1234567
    private val drumEnvelopes = DoubleArray(3)
    private val drumPhases = DoubleArray(3)
    private var rec: FloatArray = FloatArray(0)
    private var recCount = 0
    private var overdub: FloatArray? = null
    // Four independently replayable vocal-loop tracks with overdub layers.
    private val bassSlots = Array(4) { BassSettings() }

    data class LiveTrack(var pcm: FloatArray = FloatArray(0), val layers: ArrayList<FloatArray> = ArrayList(),
                         var cursor: Int = 0, @Volatile var mute: Boolean = false)
    enum class RecordState { IDLE, ARMED, COUNT_IN, RECORDING, OVERDUB }
    enum class RecordMode { FREE, FIXED }
    data class BassSettings(val seed: Int = Random.nextInt(), var probability: Float = 0.62f,
                            var warp: Float = 0.15f, var shift: Int = 0,
                            var root: Int = 36, var scale: Int = 0,
                            var mapX: Float = 0.45f, var mapY: Float = 0.45f)
    data class Grain(var clip: Clip, var phase: Double, var increment: Double, var remaining: Int,
                     val total: Int, var amplitude: Float, var pan: Float,
                     val regionStart: Double, val regionLength: Double)
    data class Shot(val clip: Clip, val sliceStart: Int, val sliceEnd: Int,
                    var phase: Double = 0.0, val speed: Double = 1.0,
                    val amplitude: Float = 0.6f, val tag: Tag = Tag.MELODIC)
    data class TouchVoice(val id: Int, var clip: Clip, @Volatile var x: Float,
                          @Volatile var y: Float, var nextGrainFrame: Long = 0,
                          var loopPhase: Double = 0.0, var smoothedStart: Double = -1.0)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        audioThread = Thread({ audioLoop() }, "BREAKFIELD-Audio").also { it.start() }
    }

    fun stop() {
        running.set(false)
        try { audioThread?.join(700) } catch (_: InterruptedException) { }
        input.stop()
    }

    fun triggerTouch(id: Int, x: Float, y: Float) = synchronized(touchLock) {
        val clip = clips.getOrNull(selectedClip) ?: return@synchronized
        touches[id] = TouchVoice(id, clip, x.coerceIn(0f, .999f), y.coerceIn(0f, 1f), sampleClock)
        touchSnapshot = touches.values.toTypedArray()
    }
    fun moveTouch(id: Int, x: Float, y: Float) = synchronized(touchLock) {
        touches[id]?.let { it.x = x.coerceIn(0f, .999f); it.y = y.coerceIn(0f, 1f) }
    }
    fun releaseTouch(id: Int) = synchronized(touchLock) {
        touches.remove(id)
        touchSnapshot = touches.values.toTypedArray()
    }
    fun releaseAllTouches() = synchronized(touchLock) {
        touches.clear()
        touchSnapshot = emptyArray()
    }

    fun toggleTransport() { playing = !playing }

    @Synchronized fun armRecording() {
        if (recordState == RecordState.ARMED || recordState == RecordState.COUNT_IN) {
            recordState = RecordState.IDLE
            return
        }
        if (recordState == RecordState.RECORDING || recordState == RecordState.OVERDUB) {
            finishRecording()
            return
        }
        if (input.isActive.not()) return
        if (recordMode == RecordMode.FREE && countInBars == 0) {
            startRecordingAtFrame()
        } else {
            countInRemaining = countInBars * 4
            recordState = RecordState.ARMED
            if (!playing) { playing = true; nextClick = sampleClock.toDouble(); nextStep = sampleClock.toDouble() }
            nextCountIn = nextBarBoundary(sampleClock.toDouble())
        }
    }

    @Synchronized fun toggleOverdub() {
        val track = liveTracks[selectedLiveTrack.coerceIn(0,3)]
        if (track.pcm.isEmpty() || !input.isActive) return
        if (recordState == RecordState.OVERDUB) {
            overdub?.let { track.layers.add(it) }
            overdub = null
            recordState = RecordState.IDLE
        } else if (recordState == RecordState.IDLE) {
            overdub = FloatArray(track.pcm.size)
            recordState = RecordState.OVERDUB
        }
    }

    @Synchronized fun snapshotLiveLoop(): FloatArray? {
        val track = liveTracks[selectedLiveTrack.coerceIn(0,3)]
        if (track.pcm.isEmpty()) return null
        return FloatArray(track.pcm.size) { i ->
            var value = track.pcm[i]
            for (layer in track.layers) value += layer[i] * overdubFeedback
            value.coerceIn(-1f, 1f)
        }
    }

    @Synchronized fun undoOverdub() {
        val track = liveTracks[selectedLiveTrack.coerceIn(0,3)]
        if (recordState != RecordState.OVERDUB && track.layers.isNotEmpty()) track.layers.removeAt(track.layers.lastIndex)
    }
    @Synchronized fun clearLiveLoop() {
        recordState = RecordState.IDLE
        rec = FloatArray(0); recCount = 0
        overdub = null
        liveTracks[selectedLiveTrack.coerceIn(0,3)] = LiveTrack()
    }
    @Synchronized fun toggleTrackMute(index: Int) {
        if (index in 0..3) liveTracks[index].mute = !liveTracks[index].mute
    }

    @Synchronized private fun startRecordingAtFrame() {
        val length = if (recordMode == RecordMode.FIXED) {
            (recordBeats * 60.0 / tempo.coerceAtLeast(20.0) * RATE).roundToInt()
        } else RATE * 120
        rec = FloatArray(length.coerceIn(1, RATE * 120))
        recCount = 0
        recordState = RecordState.RECORDING
        input.discardPending()
    }

    @Synchronized private fun finishRecording() {
        when (recordState) {
            RecordState.RECORDING -> {
                if (recCount >= RATE / 10) {
                    val track = liveTracks[selectedLiveTrack.coerceIn(0,3)]
                    track.pcm = rec.copyOf(recCount)
                    track.cursor = 0
                    track.layers.clear()
                    track.mute = false
                    loopPlaying = true
                }
                rec = FloatArray(0)
            }
            RecordState.OVERDUB -> {
                overdub?.let { liveTracks[selectedLiveTrack.coerceIn(0,3)].layers.add(it) }
                overdub = null
            }
            else -> { }
        }
        recordState = RecordState.IDLE
    }

    private fun nextBarBoundary(now: Double): Double {
        val barFrames = RATE * 60.0 / tempo.coerceAtLeast(20.0) * 4
        return (floor(now / barFrames) + 1.0) * barFrames
    }

    @Synchronized fun saveBassSlot(index: Int) {
        if (index !in 0..3) return
        bassSlots[index] = BassSettings(Random.nextInt(), bassProbability, warp, shift,
            bassRoot, scale, bassGridsX, bassGridsY)
        bassSlot = index
    }
    @Synchronized fun loadBassSlot(index: Int) {
        if (index !in 0..3) return
        bassSlot = index
        bassProbability = bassSlots[index].probability
        warp = bassSlots[index].warp
        shift = bassSlots[index].shift
        bassRoot = bassSlots[index].root
        scale = bassSlots[index].scale
        bassGridsX = bassSlots[index].mapX
        bassGridsY = bassSlots[index].mapY
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = max(8192, if (minBytes > 0) minBytes * 2 else 16384)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (_: Exception) { running.set(false); return }
        if (track.state != AudioTrack.STATE_INITIALIZED) { running.set(false); track.release(); return }
        val frameCount = 256
        val data = FloatArray(frameCount * 2)
        try {
            track.play()
            while (running.get()) {
                for (i in 0 until frameCount) {
                    val sound = renderFrame()
                    data[i * 2] = (Float.fromBits((sound ushr 32).toInt()) * masterVolume).coerceIn(-1f, 1f)
                    data[i * 2 + 1] = (Float.fromBits(sound.toInt()) * masterVolume).coerceIn(-1f, 1f)
                    sampleClock++
                }
                var offset = 0
                while (offset < data.size && running.get()) {
                    val written = track.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            }
        } finally {
            try { track.pause(); track.flush(); track.release() } catch (_: Exception) { }
        }
    }

    private fun renderFrame(): Long {
        var left = 0.0
        var right = 0.0
        val frame = sampleClock.toDouble()
        val bpm = tempo.coerceIn(30.0, 300.0)
        val framesPerBeat = RATE * 60.0 / bpm
        if (playing) {
            if (frame >= nextClick) {
                nextClick = frame + framesPerBeat
                clickPhase = 0
                clickAccent = ((frame / framesPerBeat).roundToInt() % 4 == 0)
            }
            if (frame >= nextStep) {
                val step = stepPosition % 16
                if (drumsEnabled) triggerDrums(step)
                if (bassEnabled) triggerBass(step)
                if (drumsEnabled) triggerAuxiliarySlices(step)
                stepPosition = (stepPosition + 1) % 16
                val stepFrames = framesPerBeat / 4.0
                val swingFactor = if (step % 2 == 0) 1f + swing.coerceIn(0f, .9f) * .45f else 1f - swing.coerceIn(0f, .9f) * .45f
                nextStep = frame + stepFrames * swingFactor
            }
            beatPosition = frame / framesPerBeat
        } else {
            nextStep = frame
            nextClick = frame
        }
        if (clickPhase >= 0) {
            if (click && clickPhase < 720) {
                val hz = if (clickAccent) 1550.0 else 1070.0
                val envelope = exp(-clickPhase / 110.0)
                val c = sin(TWO_PI * hz * clickPhase / RATE) * envelope * clickVolume
                left += c; right += c
            }
            clickPhase++
            if (clickPhase >= 720) clickPhase = -1
        }
        if (recordState == RecordState.ARMED || recordState == RecordState.COUNT_IN) {
            synchronized(this) {
                if (frame >= nextCountIn && recordState != RecordState.RECORDING) {
                    if (countInRemaining <= 0) startRecordingAtFrame()
                    else {
                        recordState = RecordState.COUNT_IN
                        countInRemaining--
                        nextCountIn = frame + framesPerBeat
                    }
                }
            }
        }
        val inSample = input.readSample()
        if (inputMonitor) { left += inSample * .45; right += inSample * .45 }
        synchronized(this) {
            if (recordState == RecordState.RECORDING) {
                if (recCount < rec.size) { rec[recCount++] = inSample }
                if (recCount >= rec.size) finishRecording()
            }
            if (looperEnabled && loopPlaying) {
                for (index in liveTracks.indices) {
                    val track = liveTracks[index]
                    val length = track.pcm.size
                    if (length == 0) continue
                    val cursor = track.cursor
                    var loopSample = track.pcm[cursor].toDouble()
                    for (layer in track.layers) loopSample += layer[cursor] * overdubFeedback
                    if (recordState == RecordState.OVERDUB && index == selectedLiveTrack) {
                        val layer = overdub
                        if (layer != null) { layer[cursor] += inSample; loopSample += inSample }
                    }
                    // Blend the last N samples toward the beginning of this live loop.
                    val fade = min(length / 3, (RATE * microfadeMs / 1000f).toInt())
                    if (fade > 0 && cursor >= length - fade) {
                        val blend = (cursor - (length - fade)).toDouble() / fade
                        val fromStart = cursor - (length - fade)
                        var beginning = track.pcm[fromStart].toDouble()
                        for (layer in track.layers) beginning += layer[fromStart] * overdubFeedback
                        loopSample = loopSample * (1 - blend) + beginning * blend
                    }
                    if (!track.mute) { left += loopSample * .48; right += loopSample * .48 }
                    track.cursor = if (cursor + 1 >= length) 0 else cursor + 1
                }
            }
        }
        var shotIndex = 0
        while (shotIndex < shots.size) {
            val v = shots[shotIndex]
            val sample = interpolate(v.clip.pcm, v.sliceStart + v.phase)
            val remaining = v.sliceEnd - v.sliceStart - v.phase
            val fadeFrames = (microfadeMs * RATE / 1000f).coerceAtLeast(0f).toDouble()
            val attack = if (fadeFrames <= 0) 1.0 else min(1.0, v.phase / fadeFrames)
            val release = if (fadeFrames <= 0) 1.0 else min(1.0, remaining / fadeFrames)
            val out = sample * v.amplitude * min(attack, release)
            left += out; right += out
            v.phase += v.speed
            if (v.sliceStart + v.phase >= v.sliceEnd) shots.removeAt(shotIndex) else shotIndex++
        }
        // Playback reads a snapshot so UI pointer events never lock the audio thread.
        val currentTouches = touchSnapshot
        for (touch in currentTouches) {
                val pcm = touch.clip.pcm
                if (pcm.size < 16) continue
                val start = touch.x.toDouble() * (pcm.size - 2)
                val available = pcm.size - start
                val minimum = min(available, RATE * .008)
                val length = (minimum + (available - minimum) * (1.0 - touch.y).pow(2.5)).coerceAtLeast(4.0)
                if (touch.smoothedStart < 0) touch.smoothedStart = start
                touch.smoothedStart += (start - touch.smoothedStart) * .004
                if (granular) {
                    if (sampleClock >= touch.nextGrainFrame) {
                        val nominal = (grainSizeMs * RATE / 1000f).toInt().coerceIn(24, RATE * 2)
                        val size = (nominal * (1 + (Random.nextFloat() * 2 - 1) * sizeRandom)).toInt().coerceAtLeast(12)
                        val regionPosition = ((Random.nextDouble() * 2 - 1) * startRandom * length).coerceIn(0.0, length - 1)
                        val regionStart = touch.smoothedStart.coerceIn(0.0, pcm.lastIndex.toDouble())
                        val begin = (regionStart + regionPosition).coerceIn(0.0, pcm.lastIndex.toDouble())
                        val increment = 1.0
                        if (grains.size < 192) grains.add(Grain(touch.clip, begin, increment, size, size,
                            0.68f, Random.nextFloat() * 2 - 1, regionStart, length))
                        val baseInterval = RATE / grainDensity.coerceIn(1f, 160f)
                        val variation = 1f + (Random.nextFloat() * 2 - 1) * densityRandom
                        touch.nextGrainFrame = sampleClock + (baseInterval * variation).toLong().coerceAtLeast(1L)
                    }
                } else {
                    val maxFade = min(length / 3.0, microfadeMs * RATE / 1000.0)
                    val phase = touch.loopPhase.coerceIn(0.0, length - 1.0)
                    val index = touch.smoothedStart + phase
                    var s = interpolate(pcm, index)
                    if (maxFade > 1 && phase >= length - maxFade) {
                        val frac = ((phase - (length - maxFade)) / maxFade).coerceIn(0.0, 1.0)
                        val headIndex = phase - (length - maxFade)
                        val newHead = interpolate(pcm, touch.smoothedStart + headIndex)
                        s = (s * cos(frac * PI / 2.0) + newHead * sin(frac * PI / 2.0)).toFloat()
                    }
                    left += s * .65; right += s * .65
                    touch.loopPhase += 1.0
                    if (touch.loopPhase >= length) touch.loopPhase = maxFade
                }
        }
        var grainIndex = 0
        while (grainIndex < grains.size) {
            val g = grains[grainIndex]
            val progress = (g.total - g.remaining).toDouble() / g.total
            val fade = (microfadeMs * RATE / 1000f).toDouble()
            val envelope = if (fade <= 0) 1.0 else {
                val ramp = min(fade, g.total / 2.0)
                min(1.0, min(progress * g.total / ramp, (1.0 - progress) * g.total / ramp))
            }
            val sample = interpolate(g.clip.pcm, g.phase) * envelope * g.amplitude
            left += sample * (1.0 - g.pan * .35); right += sample * (1.0 + g.pan * .35)
            g.phase += g.increment
            if (g.phase >= g.regionStart + g.regionLength) g.phase = g.regionStart +
                (g.phase - g.regionStart) % g.regionLength
            g.remaining--
            if (g.remaining <= 0 || g.phase >= g.clip.pcm.size) grains.removeAt(grainIndex) else grainIndex++
        }
        // Lightweight built-in drum voices and mono synth bass make the project playable without importing audio.
        for (i in 0..2) {
            val env = drumEnvelopes[i]
            if (env > 0.00001) {
                val freq = when (i) { 0 -> 55.0 + env * 110.0; 1 -> 155.0; else -> 0.0 }
                val v = if (i == 0) sin(drumPhases[i]) else if (i == 1) (noise() * .73 + sin(drumPhases[i]) * .27) else noise()
                val sound = v * env * when (i) { 0 -> .7; 1 -> .32; else -> .2 }
                left += sound; right += sound
                drumPhases[i] += TWO_PI * freq / RATE
                drumEnvelopes[i] *= when (i) { 0 -> .99957; 1 -> .9982; else -> .994 }
            }
        }
        if (bassEnvelope > .00001) {
            bassPhase += TWO_PI * bassFrequency / RATE
            if (bassPhase >= TWO_PI) bassPhase -= TWO_PI
            val saw = (bassPhase / PI - 1.0) * .22
            val bass = (sin(bassPhase) * .78 + saw) * bassEnvelope * bassAccent * .55
            left += bass; right += bass
            bassEnvelope *= .99982
        }
        val l = softClip(left).toBits().toLong() and 0xffffffffL
        val r = softClip(right).toBits().toLong() and 0xffffffffL
        return (l shl 32) or r
    }

    private fun triggerDrums(step: Int) {
        val patterns = arrayOf(
            intArrayOf(1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0),
            intArrayOf(0,0,0,0,1,0,0,0,0,0,0,0,1,0,0,0),
            intArrayOf(1,0,1,0,1,0,1,0,1,0,1,0,1,0,1,0),
            intArrayOf(1,0,0,1,0,0,1,0,1,0,0,1,0,0,1,0),
            intArrayOf(0,0,1,0,0,1,0,1,0,0,1,0,0,1,0,1),
            intArrayOf(1,1,0,1,1,0,1,1,0,1,1,0,1,1,0,1)
        )
        val x = gridsX.coerceIn(0f, 1f)
        val y = gridsY.coerceIn(0f, 1f)
        for (channel in 0..2) {
            val a = patterns[channel][step].toFloat()
            val b = patterns[channel + 3][step].toFloat()
            val other = patterns[(channel + 1) % 3][step].toFloat()
            val chance = ((a * (1 - x) + b * x) * (1 - y * .5f) + other * y * .5f) * drumProbability
            val tag = when(channel) { 0 -> Tag.KICK; 1 -> Tag.SNARE; else -> Tag.HAT }
            val tagProbability = when(channel) { 0 -> kickProbability; 1 -> snareProbability; else -> hatProbability }
            if (Random.nextFloat() < (chance + (Random.nextFloat() - .5f) * chaos).coerceIn(0f, 1f) * tagProbability) {
                drumEnvelopes[channel] = 1.0
                drumPhases[channel] = 0.0
                if (playTaggedSlice(tag, .48f)) drumEnvelopes[channel] = 0.0
            }
        }
    }


    private fun triggerAuxiliarySlices(step: Int) {
        if (step % 2 == 0 && Random.nextFloat() < sliceBassProbability * drumProbability * .5f)
            playTaggedSlice(Tag.BASS, .38f)
        if (Random.nextFloat() < melodicProbability * drumProbability * .18f)
            playTaggedSlice(Tag.MELODIC, .32f)
    }

    private fun playTaggedSlice(tag: Tag, amp: Float): Boolean {
        var chosenClip: Clip? = null
        var chosenSlice: com.breakfield.model.Slice? = null
        var seen = 0
        for (clip in clips) for (slice in clip.slices) {
            if (slice.tag != tag) continue
            seen++
            if (Random.nextInt(seen) == 0) { chosenClip = clip; chosenSlice = slice }
        }
        val clip = chosenClip ?: return false
        val slice = chosenSlice ?: return false
        if (chokeMode == 0) shots.clear()
        if (chokeMode == 1) shots.removeAll { it.tag == tag }
        if (shots.size >= 64) shots.removeAt(0)
        shots.add(Shot(clip,slice.start,slice.end,amplitude=amp,tag=tag))
        return true
    }

    private fun triggerBass(step: Int) {
        val slot = bassSlots[bassSlot.coerceIn(0,3)]
        val rotatedStep = ((step + shift) % 16 + 16) % 16
        val structural = if (rotatedStep % 4 == 0) .92f else if (rotatedStep % 2 == 0) .58f else .24f
        val bx = if (bassFollowDrums) gridsX else bassGridsX
        val by = if (bassFollowDrums) gridsY else bassGridsY
        val mapGate = ((1f-bx)*structural + bx*(if (rotatedStep % 3 == 0) .88f else .18f)) *
            (1f-by*.45f) + by*.45f*(if (rotatedStep % 5 == 0) 1f else .25f)
        if (Random.nextFloat() >= bassProbability * (mapGate + warp * Random.nextFloat()*.35f).coerceAtMost(1f)) return
        val scaleNotes = when (scale) {
            1 -> intArrayOf(0,2,4,5,7,9,11)
            2 -> intArrayOf(0,2,3,5,7,9,10)
            3 -> intArrayOf(0,1,3,5,7,8,10)
            4 -> intArrayOf(0,3,5,7,10)
            else -> intArrayOf(0,2,3,5,7,8,10)
        }
        val lattice = (bx * 7 + by * 11).roundToInt()
        val index = (rotatedStep * 3 + (slot.seed.toLong().absoluteValue % 13).toInt() + lattice + Random.nextInt(0,3)) % scaleNotes.size
        val midi = (bassRoot + scaleNotes[index] + if (Random.nextFloat() < warp * .4f) 12 else 0).coerceIn(20, 85)
        bassFrequency = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
        bassEnvelope = 1.0
        bassAccent = if (step % 4 == 0) 1.0 else .68
    }

    private fun noise(): Double {
        noiseSeed = noiseSeed xor (noiseSeed shl 13)
        noiseSeed = noiseSeed xor (noiseSeed ushr 17)
        noiseSeed = noiseSeed xor (noiseSeed shl 5)
        return (noiseSeed.toLong() and 0xffffffffL).toDouble() / 2147483648.0 - 1.0
    }
    private fun interpolate(buffer: FloatArray, index: Double): Float {
        if (buffer.isEmpty() || index < 0 || index >= buffer.size) return 0f
        val i = index.toInt()
        val j = min(i + 1, buffer.lastIndex)
        val f = (index - i).toFloat()
        return buffer[i] * (1 - f) + buffer[j] * f
    }
    private fun softClip(input: Double): Float = (input / (1 + abs(input) * .3)).toFloat()
}
