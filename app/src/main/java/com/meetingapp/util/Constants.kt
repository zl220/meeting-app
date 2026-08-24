package com.meetingapp.util

object Constants {
    const val OPENAI_BASE_URL = "https://api.openai.com/v1/"

    const val CHUNK_DURATION_MS = 8_000L
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT

    const val AI_WAKE_NAME = "小谈"
    const val AUDIO_RETENTION_DAYS = 30

    const val NOTIF_CHANNEL_RECORDING = "recording_channel"
    const val NOTIF_ID_RECORDING = 1001

    const val MODEL_TRANSCRIBE = "whisper-1"
    const val MODEL_CHAT = "gpt-4o"
    const val MODEL_TTS = "tts-1"
    const val TTS_VOICE = "nova"
}
