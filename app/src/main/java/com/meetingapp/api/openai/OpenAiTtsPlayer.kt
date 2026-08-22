package com.meetingapp.api.openai

import android.media.MediaPlayer
import com.meetingapp.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiTtsPlayer @Inject constructor(
    private val service: OpenAiService,
    @Named("openai_api_key_flow") private val apiKeyFlow: Flow<String>,
    @Named("cache_dir") private val cacheDir: File
) {
    private var currentPlayer: MediaPlayer? = null

    suspend fun speak(text: String): Unit = withContext(Dispatchers.IO) {
        val apiKey = apiKeyFlow.first()
        val request = TtsRequest(
            model = Constants.MODEL_TTS,
            input = text,
            voice = Constants.TTS_VOICE
        )
        val body = service.tts("Bearer $apiKey", request)
        val tmpFile = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        FileOutputStream(tmpFile).use { out ->
            out.write(body.bytes())
        }
        withContext(Dispatchers.Main) {
            playFile(tmpFile)
        }
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        currentPlayer = player
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                player.release()
                file.delete()
                if (cont.isActive) cont.resume(Unit)
            }
            player.setOnErrorListener { _, _, _ ->
                player.release()
                file.delete()
                if (cont.isActive) cont.resumeWithException(RuntimeException("MediaPlayer error"))
                true
            }
            cont.invokeOnCancellation {
                player.stop()
                player.release()
                file.delete()
            }
            player.start()
        } catch (e: Exception) {
            player.release()
            file.delete()
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    fun interrupt() {
        currentPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        currentPlayer = null
    }
}
