package com.meetingapp.service

import com.meetingapp.util.Constants
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts a short sub-clip out of a 16 kHz mono 16-bit WAV file. Used to extract a 2–10s voice
 * reference for a speaker's turn from a diarization window WAV. All 16-bit mono PCM assumptions
 * match [AudioChunkWriter]/[WindowWavWriter] output.
 */
object WavClip {

    private const val HEADER_BYTES = 44

    /**
     * Extract [fromMs, toMs) (relative to the file's audio start) into [dest], clamped to
     * [Constants.VOICE_SAMPLE_MIN_MS]..[Constants.VOICE_SAMPLE_MAX_MS]. Returns the actual
     * clip duration in ms, or null if the source is too short/invalid.
     */
    fun extract(source: File, fromMs: Long, toMs: Long, dest: File): Long? {
        if (!source.exists() || source.length() <= HEADER_BYTES) return null
        val allPcm = try {
            val bytes = source.readBytes()
            bytes.copyOfRange(HEADER_BYTES, bytes.size)
        } catch (_: Exception) {
            return null
        }

        val rawDur = (toMs - fromMs).coerceAtLeast(0)
        // Grow a too-short turn up to the minimum, and cap an over-long one.
        val targetMs = rawDur.coerceIn(Constants.VOICE_SAMPLE_MIN_MS, Constants.VOICE_SAMPLE_MAX_MS)
        var startByte = msToBytes(fromMs)
        var lenBytes = msToBytes(targetMs)
        // Clamp to the available audio.
        if (startByte >= allPcm.size) return null
        if (startByte + lenBytes > allPcm.size) {
            lenBytes = allPcm.size - startByte
        }
        if (bytesToMs(lenBytes.toLong()) < Constants.VOICE_SAMPLE_MIN_MS) {
            // Not enough audio after fromMs; try pulling the window's start instead.
            startByte = 0
            lenBytes = msToBytes(Constants.VOICE_SAMPLE_MIN_MS).coerceAtMost(allPcm.size)
        }
        if (lenBytes <= 0) return null

        val clip = allPcm.copyOfRange(startByte, startByte + lenBytes)
        FileOutputStream(dest).use { fos ->
            fos.write(buildWavHeader(clip.size, clip.size + 36))
            fos.write(clip)
        }
        return bytesToMs(clip.size.toLong())
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

    private fun msToBytes(ms: Long): Int {
        // Align to a 2-byte sample boundary so we never split a sample.
        val raw = (ms * Constants.SAMPLE_RATE_HZ * 2L / 1000).toInt()
        return raw - (raw % 2)
    }

    private fun bytesToMs(bytes: Long): Long = bytes / (Constants.SAMPLE_RATE_HZ * 2L / 1000)
}
