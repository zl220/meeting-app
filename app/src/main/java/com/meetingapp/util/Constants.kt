package com.meetingapp.util

object Constants {
    const val OPENAI_BASE_URL = "https://api.openai.com/v1/"

    const val CHUNK_DURATION_MS = 8_000L
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT

    const val AI_WAKE_NAME = "小谈"
    const val AUDIO_RETENTION_DAYS = 30

    // whisper-1 does NOT do speaker diarization, so every transcribed chunk gets this
    // single neutral label. Users can tap a segment to manually assign a real name.
    const val SPEAKER_LABEL_DEFAULT = "发言"

    // Live rolling minutes (R10): refresh the draft once this many new transcript
    // chars accumulate (~400 zh chars ≈ 2 min of meeting speech), throttled by a
    // minimum interval so fast talkers don't trigger back-to-back API calls.
    const val MINUTES_REFRESH_CHARS = 400
    const val MINUTES_REFRESH_MIN_INTERVAL_MS = 45_000L

    const val NOTIF_CHANNEL_RECORDING = "recording_channel"
    const val NOTIF_ID_RECORDING = 1001

    const val MODEL_TRANSCRIBE = "whisper-1"
    const val MODEL_CHAT = "gpt-4o"
    const val MODEL_TTS = "tts-1"
    const val TTS_VOICE = "nova"
}
