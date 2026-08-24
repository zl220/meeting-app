package com.meetingapp.api.openai

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.content.Context
import com.meetingapp.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @Named("cache_dir") private val cacheDir: File,
    @ApplicationContext private val context: Context
) {
    private var currentPlayer: MediaPlayer? = null

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

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
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .build()
        audioManager.requestAudioFocus(focusRequest)

        val player = MediaPlayer()
        currentPlayer = player
        try {
            player.setAudioAttributes(audioAttrs)
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                player.release()
                file.delete()
                audioManager.abandonAudioFocusRequest(focusRequest)
                if (cont.isActive) cont.resume(Unit)
            }
            player.setOnErrorListener { _, _, _ ->
                player.release()
                file.delete()
                if (cont.isActive) cont.resumeWithException(RuntimeException("MediaPlayer error"))
                true
            }
            cont.invokeOnCancellation {
                currentPlayer = null
                try { player.stop() } catch (_: Exception) {}
                try { player.release() } catch (_: Exception) {}
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
        val player = currentPlayer ?: return
        currentPlayer = null
        try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
        try { player.release() } catch (_: Exception) {}
    }
}
