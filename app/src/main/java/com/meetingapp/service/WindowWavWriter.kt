package com.meetingapp.service

import com.meetingapp.util.Constants
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Buffers PCM into ~5-minute WAV "windows" for in-meeting speaker diarization, running
 * alongside [AudioChunkWriter] (8s transcription chunks) and [FullAudioRecorder] (full M4A).
 *
 * Unlike the chunk writer, a window is NOT silence-filtered as a whole (a 5-minute window
 * always contains speech); instead it keeps ALL audio so diarized turn timestamps line up
 * with the meeting clock. To avoid cutting mid-sentence, a window is only flushed once its
 * target duration is reached AND the current tail is silent — up to a hard cap.
 *
 * A small trailing overlap is prepended to the next window so a speaker turn straddling the
 * boundary isn't lost. Not thread-safe: driven from the single recording read loop.
 */
class WindowWavWriter(private val outputDir: File) {

    private val buffer = mutableListOf<ByteArray>()
    private var bufferSizeBytes = 0
    private var windowStartMs = 0L
    // Carried over from the previous window so a boundary-straddling turn survives.
    private var overlapPcm: ByteArray = ByteArray(0)

    fun start() {
        buffer.clear()
        bufferSizeBytes = 0
        overlapPcm = ByteArray(0)
        windowStartMs = System.currentTimeMillis()
    }

    fun write(data: ByteArray) {
        buffer.add(data.copyOf())
        bufferSizeBytes += data.size
    }

    /**
     * A window is ready when it has reached the target duration and the tail is silent
     * (clean cut), or once it hits a hard cap (target + one extra window) to bound size
     * even in nonstop speech.
     */
    fun isWindowReady(latestSlice: ByteArray?): Boolean {
        val durationMs = bytesToMs(bufferSizeBytes.toLong())
        if (durationMs < Constants.DIARIZE_WINDOW_MS) return false
        val hardCapMs = Constants.DIARIZE_WINDOW_MS * 2
        if (durationMs >= hardCapMs) return true
        return latestSlice != null && isSilent(latestSlice)
    }

    /**
     * Flush the current window to a WAV file (with the previous overlap prepended). Retains a
     * trailing [Constants.DIARIZE_WINDOW_OVERLAP_MS] slice as the next window's overlap.
     * Returns the window file with its meeting-relative start, or null if empty.
     */
    fun flushWindow(): WindowFile? {
        if (buffer.isEmpty()) return null
        val body = buffer.flattenToBytes()
        // Incoming overlap (from the previous window) prepended to the front of this file.
        val incomingOverlap = overlapPcm
        val pcm = if (incomingOverlap.isEmpty()) body else incomingOverlap + body

        val startMs = windowStartMs
        val endMs = System.currentTimeMillis()

        // Keep the tail of THIS window's body as the next window's overlap.
        val overlapBytes = msToBytes(Constants.DIARIZE_WINDOW_OVERLAP_MS)
            .coerceAtMost(body.size)
        overlapPcm = if (overlapBytes > 0) body.copyOfRange(body.size - overlapBytes, body.size)
                     else ByteArray(0)
        buffer.clear()
        bufferSizeBytes = 0
        // Next window's non-overlap audio starts at this window's end; its file will begin
        // one overlap earlier, which the fileStartMs of that window will account for.
        windowStartMs = endMs

        val file = writeWav(pcm)
        // The prepended incoming overlap means the file's audio starts that many ms before
        // startMs. Report that earlier origin so diarized seconds map onto the meeting clock.
        val prependedMs = bytesToMs(incomingOverlap.size.toLong())
        val fileStartMs = (startMs - prependedMs).coerceAtLeast(0)
        return WindowFile(file, fileStartMs, endMs)
    }

    private fun isSilent(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return true
        var sumSq = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sumSq += sample * sample.toDouble()
            i += 2
        }
        val rms = Math.sqrt(sumSq / (pcm.size / 2))
        return rms < SILENCE_RMS_THRESHOLD
    }

    private fun writeWav(pcm: ByteArray): File {
        val file = File(outputDir, "window_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { fos ->
            fos.write(buildWavHeader(pcm.size, pcm.size + 36))
            fos.write(pcm)
        }
        return file
    }

    private fun buildWavHeader(pcmSize: Int, totalDataLen: Int): ByteArray {
        val sampleRate = Constants.SAMPLE_RATE_HZ
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(totalDataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort()); putShort(bitsPerSample.toShort())
            put("data".toByteArray()); putInt(pcmSize)
        }.array()
    }

    private fun bytesToMs(bytes: Long): Long = bytes / (Constants.SAMPLE_RATE_HZ * 2L / 1000)
    private fun msToBytes(ms: Long): Int = (ms * Constants.SAMPLE_RATE_HZ * 2L / 1000).toInt()

    companion object {
        private const val SILENCE_RMS_THRESHOLD = 150.0
    }
}

/** A diarization window WAV with its meeting-relative start (accounting for prepended overlap). */
data class WindowFile(val file: File, val startMs: Long, val endMs: Long)

private fun List<ByteArray>.flattenToBytes(): ByteArray {
    val total = sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    for (arr in this) { arr.copyInto(result, offset); offset += arr.size }
    return result
}
