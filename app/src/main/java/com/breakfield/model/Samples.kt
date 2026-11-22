package com.breakfield.model

import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Tags are performance categories, not claims of reliable source separation. */
enum class Tag(val display: String) {
    KICK("Kick"), SNARE("Snare"), HAT("Hat"), BASS("Bass"), MELODIC("Melodic")
}

data class Slice(var start: Int, var end: Int, var tag: Tag)

class Clip(val name: String, val pcm: FloatArray) {
    val slices: MutableList<Slice> = CopyOnWriteArrayList()

    init { autoSlice() }

    fun autoSlice() {
        slices.clear()
        if (pcm.isEmpty()) return
        val block = 256
        val count = max(1, pcm.size / block)
        val energy = FloatArray(count)
        for (b in 0 until count) {
            val from = b * block
            var sum = 0.0
            val to = min(pcm.size, from + block)
            for (i in from until to) sum += (pcm[i] * pcm[i]).toDouble()
            energy[b] = sqrt(sum / max(1, to - from)).toFloat()
        }
        val smooth = FloatArray(count)
        for (b in 0 until count) {
            var sum = 0f
            for (k in max(0, b - 12)..min(count - 1, b + 2)) sum += energy[k]
            smooth[b] = sum / (min(count - 1, b + 2) - max(0, b - 12) + 1)
        }
        val sorted = energy.copyOf()
        sorted.sort()
        val median = sorted[sorted.size / 2]
        val minSpacing = 48000 / block / 9
        val starts = mutableListOf(0)
        for (b in 2 until count) {
            val novelty = energy[b] - smooth[b - 1]
            if (novelty > max(0.023f, median * 0.6f) &&
                energy[b] > energy[b - 1] * 1.12f &&
                b - (starts.last() / block) >= minSpacing && starts.size < 128) {
                starts.add(b * block)
            }
        }
        if (starts.size < 3) {
            starts.clear()
            val countSlices = min(16, max(1, pcm.size / 6000))
            repeat(countSlices) { starts.add(it * pcm.size / countSlices) }
        }
        for (i in starts.indices) {
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else pcm.size
            if (end > start) slices.add(Slice(start, end, guessTag(start, end)))
        }
    }

    private fun guessTag(start: Int, end: Int): Tag {
        val n = min(4096, end - start)
        if (n < 5) return Tag.MELODIC
        var crossings = 0
        var amplitude = 0.0
        for (i in start + 1 until start + n) {
            if ((pcm[i] >= 0) != (pcm[i - 1] >= 0)) crossings++
            amplitude += abs(pcm[i].toDouble())
        }
        val rate = crossings.toDouble() / n
        return when {
            rate < .025 -> Tag.KICK
            rate > .24 -> Tag.HAT
            rate > .12 -> Tag.SNARE
            amplitude / n > .13 -> Tag.BASS
            else -> Tag.MELODIC
        }
    }

    fun addSlice(at: Int) {
        val point = at.coerceIn(1, pcm.size - 1)
        val sliceIndex = slices.indexOfFirst { point > it.start + 128 && point < it.end - 128 }
        if (sliceIndex < 0) return
        val found = slices[sliceIndex]
        slices[sliceIndex] = Slice(found.start, point, found.tag)
        slices.add(sliceIndex + 1, Slice(point, found.end, found.tag))
    }
}

/** Small, dependency-free WAV reader: PCM16 and IEEE-float32, mono/stereo, resampled to 48 kHz. */
object WavReader {
    fun read(input: InputStream, name: String): Clip {
        val bytes = input.use { it.readBytes() }
        require(bytes.size <= 64 * 1024 * 1024) { "Audio file exceeds the 64 MB import limit" }
        require(bytes.size >= 44) { "Not a valid WAV file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(position: Int) = String(bytes, position, 4, Charsets.US_ASCII)
        require(ascii(0) == "RIFF" && ascii(8) == "WAVE") { "Choose a RIFF WAV file" }
        var offset = 12
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitDepth = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val size = b.getInt(offset + 4)
            if (size < 0 || offset + 8L + size > bytes.size) break
            when (ascii(offset)) {
                "fmt " -> if (size >= 16) {
                    format = b.getShort(offset + 8).toInt() and 65535
                    channels = b.getShort(offset + 10).toInt() and 65535
                    sampleRate = b.getInt(offset + 12)
                    bitDepth = b.getShort(offset + 22).toInt() and 65535
                }
                "data" -> { dataOffset = offset + 8; dataSize = size }
            }
            offset += 8 + size + (size and 1)
        }
        require(channels in 1..2 && sampleRate in 8000..192000 && dataOffset >= 0) { "Unsupported WAV format" }
        require((format == 1 && bitDepth == 16) || (format == 3 && bitDepth == 32)) {
            "Supported WAV formats: PCM16 and float32"
        }
        val sampleBytes = bitDepth / 8
        val frames = dataSize / (sampleBytes * channels)
        require(frames > 0) { "Audio file has no samples" }
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) {
                val p = dataOffset + (i * channels + ch) * sampleBytes
                sum += if (format == 1) b.getShort(p) / 32768f else b.getFloat(p).let { if (it.isFinite()) it else 0f }
            }
            mono[i] = (sum / channels).coerceIn(-1f, 1f)
        }
        val targetSize = max(1, (mono.size.toDouble() * 48000.0 / sampleRate).roundToInt())
        require(targetSize < 48_000 * 60 * 10) { "Clip exceeds 10 minutes" }
        val pcm = if (sampleRate == 48000) mono else FloatArray(targetSize) { i ->
            val position = i * sampleRate.toDouble() / 48000.0
            val low = position.toInt().coerceIn(0, mono.lastIndex)
            val high = min(low + 1, mono.lastIndex)
            val fraction = (position - low).toFloat()
            mono[low] * (1 - fraction) + mono[high] * fraction
        }
        return Clip(name, pcm)
    }
}
