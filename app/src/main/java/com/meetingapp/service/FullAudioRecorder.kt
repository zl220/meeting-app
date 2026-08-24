package com.meetingapp.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.meetingapp.util.Constants
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes the entire meeting's raw PCM into ONE continuous M4A/AAC file.
 *
 * Runs alongside [AudioChunkWriter]: the recording read loop feeds every PCM
 * buffer to both. Unlike the chunk writer, this recorder keeps silence, so the
 * result is a gap-free recording of the whole meeting.
 *
 * Not thread-safe: [write]/[stop] must be called from the single recording loop.
 */
class FullAudioRecorder(private val outputFile: File) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationTimeUs = 0L
    private var started = false

    fun start() {
        if (started) return
        val sampleRate = Constants.SAMPLE_RATE_HZ
        val channels = 1

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        started = true
    }

    /** Feed one PCM buffer (16-bit mono, matching the AudioRecord config). */
    fun write(pcm: ByteArray) {
        val codec = codec ?: return
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(pcm)
            codec.queueInputBuffer(inputIndex, 0, pcm.size, presentationTimeUs, 0)
            // 16-bit mono => 2 bytes per frame; advance the presentation clock.
            val frames = pcm.size / 2
            presentationTimeUs += frames * 1_000_000L / Constants.SAMPLE_RATE_HZ
        }
        drainEncoder(endOfStream = false)
    }

    /** Flush the encoder, finalize the container, and return the file (or null on failure). */
    fun stop(): File? {
        if (!started) return null
        val codec = codec
        if (codec != null) {
            try {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                drainEncoder(endOfStream = true)
            } catch (_: Exception) {
                // best-effort flush
            }
        }
        release()
        return if (outputFile.exists() && outputFile.length() > 0) outputFile else null
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output ready. Keep looping only while flushing at EOS.
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) return
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outputIndex) ?: continue
                    val isCodecConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isCodecConfig && bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
        started = false
    }

    companion object {
        private const val AAC_BIT_RATE = 64_000
        private const val MAX_INPUT_SIZE = 16 * 1024
        private const val TIMEOUT_US = 10_000L
    }
}
