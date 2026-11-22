package com.breakfield.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Android-managed microphone and USB Audio Class recording. Input routing is a preference,
 * so callers should inspect routedInput after recording begins.
 */
class InputCapture {
    private val running = AtomicBoolean(false)
    private val ring = FloatArray(RATE * 2)
    @Volatile private var writer = 0L
    @Volatile private var reader = 0L
    @Volatile var isActive = false
        private set
    @Volatile var routedInput = "None"
        private set
    @Volatile var lastError = ""
        private set
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    fun availableInputs(context: Context): List<AudioDeviceInfo> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
    }

    fun start(context: Context, preferred: AudioDeviceInfo?): Boolean {
        stop()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Microphone permission required"
            return false
        }
        val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
            lastError = "48 kHz microphone input unavailable"
            return false
        }
        try {
            val input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(minBytes * 2, 8192))
                .build()
            if (input.state != AudioRecord.STATE_INITIALIZED) {
                input.release(); lastError = "Could not initialise recording"; return false
            }
            if (preferred != null && !input.setPreferredDevice(preferred)) {
                input.release(); lastError = "The selected input is unavailable"; return false
            }
            record = input
            writer = 0; reader = 0
            running.set(true)
            input.startRecording()
            if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                running.set(false); input.release(); record = null
                lastError = "Audio recording could not start"
                return false
            }
            isActive = true
            routedInput = input.routedDevice?.productName?.toString() ?: "Android default input"
            lastError = ""
            thread = Thread({ captureLoop(input) }, "BREAKFIELD-Input").also { it.start() }
            return true
        } catch (error: Exception) {
            running.set(false)
            try { record?.release() } catch (_: Exception) { }
            record = null
            lastError = error.message ?: "Input permission or device error"
            return false
        }
    }

    private fun captureLoop(input: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ShortArray(512)
        try {
            while (running.get()) {
                val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                for (i in 0 until count) {
                    val position = writer
                    ring[(position % ring.size).toInt()] = buffer[i] / 32768f
                    writer = position + 1
                    if (writer - reader > ring.size) reader = writer - ring.size
                }
                routedInput = input.routedDevice?.productName?.toString() ?: routedInput
            }
        } catch (_: Exception) {
            lastError = "Input disconnected or recording interrupted"
        } finally {
            isActive = false
        }
    }

    fun readSample(): Float {
        if (reader >= writer) return 0f
        val position = reader
        val sample = ring[(position % ring.size).toInt()]
        reader = position + 1
        return sample
    }

    fun discardPending() { reader = writer }

    fun stop() {
        running.set(false)
        try { record?.stop() } catch (_: Exception) { }
        try { thread?.join(350) } catch (_: InterruptedException) { }
        try { record?.release() } catch (_: Exception) { }
        record = null
        thread = null
        isActive = false
        routedInput = "None"
    }
}
