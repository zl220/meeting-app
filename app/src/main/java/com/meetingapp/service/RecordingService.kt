package com.meetingapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.meetingapp.R
import com.meetingapp.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    @Inject lateinit var chunkCallback: ChunkCallback

    private val binder = RecordingBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var chunkWriter: AudioChunkWriter? = null
    private var fullAudioRecorder: FullAudioRecorder? = null
    private var fullAudioFile: File? = null
    private var meetingId: Long = -1

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    @Volatile private var audioPaused = false

    interface ChunkCallback {
        suspend fun onChunkReady(meetingId: Long, chunk: ChunkFile)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                meetingId = intent.getLongExtra(EXTRA_MEETING_ID, -1)
                // Must promote to foreground immediately to avoid ANR/kill on Android 12+.
                // Actual audio recording is started by the bound client via startRecording()
                // from onServiceConnected — avoids double-start race.
                startForeground(Constants.NOTIF_ID_RECORDING, buildNotification())
                acquireWakeLock()
            }
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    fun startRecording() {
        if (_isRecording.value) return

        val bufferSize = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE_HZ,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT
        ).coerceAtLeast(8192)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Constants.SAMPLE_RATE_HZ,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        audioRecord = record

        val audioDir = File(filesDir, "audio/$meetingId").also { it.mkdirs() }
        chunkWriter = AudioChunkWriter(audioDir).also { it.start() }
        // Full continuous recording of the whole meeting (keeps silence).
        fullAudioFile = File(audioDir, "meeting_$meetingId.m4a")
        fullAudioRecorder = try {
            FullAudioRecorder(fullAudioFile!!).also { it.start() }
        } catch (_: Exception) {
            fullAudioFile = null
            null   // full-file recording is best-effort; chunk transcription still works
        }

        audioRecord!!.startRecording()
        _isRecording.value = true

        // Separate coroutine for chunk processing so it never blocks the read loop
        val chunkChannel = kotlinx.coroutines.channels.Channel<ChunkFile>(capacity = 8)
        scope.launch {
            for (chunk in chunkChannel) {
                chunkCallback.onChunkReady(meetingId, chunk)
            }
        }

        scope.launch {
            val readBuffer = ByteArray(bufferSize)
            while (_isRecording.value) {
                if (audioPaused) {
                    kotlinx.coroutines.delay(50)
                    _amplitude.value = 0f
                    continue
                }
                val read = audioRecord?.read(readBuffer, 0, bufferSize) ?: break
                if (read > 0) {
                    val writer = chunkWriter ?: break
                    val slice = readBuffer.copyOf(read)
                    writer.write(slice)
                    fullAudioRecorder?.write(slice)
                    // Update amplitude for waveform animation — boosted for visibility
                    _amplitude.value = (computeRms(slice) * 8f).coerceIn(0f, 1f)
                    if (writer.isChunkReady()) {
                        writer.flushChunk()?.let { chunk ->
                            chunkChannel.trySend(chunk)
                        }
                    }
                }
            }
            chunkChannel.close()
        }
    }

    fun stopRecording(): ChunkFile? {
        _isRecording.value = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        val finalChunk = chunkWriter?.flushChunk()
        chunkWriter = null
        // Detach first so the read loop can't write to it while we finalize.
        val fullRecorder = fullAudioRecorder
        fullAudioRecorder = null
        fullRecorder?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return finalChunk
    }

    /** Path of the full meeting recording, valid after [stopRecording]. Null if unavailable. */
    fun fullAudioFilePath(): String? =
        fullAudioFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath

    fun flushCurrentChunk(): ChunkFile? = chunkWriter?.flushChunk()

    /** Temporarily release the microphone so SpeechRecognizer can use it. */
    fun pauseForSpeechRecognizer() {
        audioPaused = true
        audioRecord?.stop()
    }

    /** Resume recording after SpeechRecognizer or TTS is done. */
    fun resumeAfterSpeechRecognizer() {
        if (!audioPaused) return
        audioPaused = false
        audioRecord?.startRecording()
    }

    private fun computeRms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sumSq += sample * sample.toDouble()
            i += 2
        }
        return (Math.sqrt(sumSq / (pcm.size / 2)) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.recording_in_progress))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(Constants.NOTIF_CHANNEL_RECORDING) != null) return
        val channel = NotificationChannel(
            Constants.NOTIF_CHANNEL_RECORDING,
            "录音",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeetingApp::RecordingWakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meetingapp.START_RECORDING"
        const val ACTION_STOP = "com.meetingapp.STOP_RECORDING"
        const val EXTRA_MEETING_ID = "meeting_id"
    }
}
