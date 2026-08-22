package com.meetingapp.service

import com.meetingapp.util.Constants
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioChunkWriter(private val outputDir: File) {

    private val buffer = mutableListOf<ByteArray>()
    private var bufferSizeBytes = 0
    private var chunkStartMs = 0L

    fun start() {
        buffer.clear()
        bufferSizeBytes = 0
        chunkStartMs = System.currentTimeMillis()
    }

    fun write(data: ByteArray) {
        buffer.add(data.copyOf())
        bufferSizeBytes += data.size
    }

    fun isChunkReady(): Boolean {
        val durationMs = bytesToMs(bufferSizeBytes.toLong())
        return durationMs >= Constants.CHUNK_DURATION_MS
    }

    fun flushChunk(): ChunkFile? {
        if (buffer.isEmpty()) return null
        val file = writeWav(buffer.flatten().toByteArray())
        val startMs = chunkStartMs
        val endMs = System.currentTimeMillis()
        buffer.clear()
        bufferSizeBytes = 0
        chunkStartMs = endMs
        return ChunkFile(file, startMs, endMs)
    }

    private fun writeWav(pcm: ByteArray): File {
        val file = File(outputDir, "chunk_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { fos ->
            val totalDataLen = pcm.size + 36
            fos.write(buildWavHeader(pcm.size, totalDataLen))
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
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmSize)
        }.array()
    }

    private fun bytesToMs(bytes: Long): Long {
        val bytesPerMs = Constants.SAMPLE_RATE_HZ * 2L / 1000
        return bytes / bytesPerMs
    }
}

data class ChunkFile(val file: File, val startMs: Long, val endMs: Long)

private fun List<ByteArray>.flatten(): ByteArray {
    val total = sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    for (arr in this) {
        arr.copyInto(result, offset)
        offset += arr.size
    }
    return result
}
