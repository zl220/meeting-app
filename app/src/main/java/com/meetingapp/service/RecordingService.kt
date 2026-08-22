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
    private var meetingId: Long = -1

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

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
                startRecording()
            }
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    fun startRecording() {
        if (_isRecording.value) return
        startForeground(Constants.NOTIF_ID_RECORDING, buildNotification())
        acquireWakeLock()

        val bufferSize = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE_HZ,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Constants.SAMPLE_RATE_HZ,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT,
            bufferSize
        )

        val audioDir = File(filesDir, "audio/$meetingId").also { it.mkdirs() }
        chunkWriter = AudioChunkWriter(audioDir).also { it.start() }

        audioRecord!!.startRecording()
        _isRecording.value = true

        scope.launch {
            val readBuffer = ByteArray(bufferSize)
            while (_isRecording.value) {
                val read = audioRecord?.read(readBuffer, 0, bufferSize) ?: break
                if (read > 0) {
                    val writer = chunkWriter ?: break
                    writer.write(readBuffer.copyOf(read))
                    if (writer.isChunkReady()) {
                        writer.flushChunk()?.let { chunk ->
                            chunkCallback.onChunkReady(meetingId, chunk)
                        }
                    }
                }
            }
        }
    }

    fun stopRecording(): ChunkFile? {
        _isRecording.value = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        val finalChunk = chunkWriter?.flushChunk()
        chunkWriter = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return finalChunk
    }

    fun flushCurrentChunk(): ChunkFile? = chunkWriter?.flushChunk()

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
