package com.meetingapp.service

import android.content.Context
import android.util.Log
import com.meetingapp.data.db.dao.AudioChunkDao
import com.meetingapp.data.db.dao.MeetingDao
import com.meetingapp.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes meeting audio (per-chunk WAVs and the full M4A) once a meeting is older than
 * [Constants.AUDIO_RETENTION_DAYS]. Without this, files under filesDir/audio/<meetingId>/
 * accumulate forever. Meeting/segment/minutes rows are kept — only the audio is purged.
 */
@Singleton
class AudioCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meetingDao: MeetingDao,
    private val audioChunkDao: AudioChunkDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Fire-and-forget sweep, safe to call on every app start. */
    fun sweep() {
        scope.launch { runCatching { sweepBlocking() }.onFailure { Log.e(TAG, "sweep failed", it) } }
    }

    private suspend fun sweepBlocking() {
        val cutoffMs = System.currentTimeMillis() -
            TimeUnit.DAYS.toMillis(Constants.AUDIO_RETENTION_DAYS.toLong())

        // Delete the on-disk audio directory for every meeting past the retention window.
        val audioRoot = File(context.filesDir, "audio")
        val stale = meetingDao.getAllOnce().filter { m ->
            val ref = m.startedAt ?: m.endedAt
            ref != null && ref < cutoffMs
        }
        var freedDirs = 0
        for (m in stale) {
            val dir = File(audioRoot, m.id.toString())
            if (dir.exists() && dir.deleteRecursively()) freedDirs++
            // Null out the now-dangling path so playback/export UIs don't point at a gone file.
            if (m.audioFilePath != null) meetingDao.setAudioFilePath(m.id, null)
        }

        // Drop the AudioChunk rows for those meetings (their WAVs are gone).
        audioChunkDao.deleteOlderThan(cutoffMs)

        // Belt and suspenders: remove any orphaned audio dirs with no meeting row at all.
        audioRoot.listFiles()?.forEach { dir ->
            val id = dir.name.toLongOrNull()
            if (dir.isDirectory && id != null && meetingDao.getById(id) == null) {
                dir.deleteRecursively()
            }
        }

        if (freedDirs > 0) Log.d(TAG, "Purged audio for $freedDirs meeting(s) older than ${Constants.AUDIO_RETENTION_DAYS}d")
    }

    private companion object {
        const val TAG = "AudioCleaner"
    }
}
