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

    // --- Speaker diarization (gpt-4o-transcribe-diarize) ---
    // Specialized speaker-labeling model. Runs in-meeting on ~5-minute windows (NOT the
    // realtime path — diarization is only offered on /v1/audio/transcriptions) to backfill
    // "who said what" onto the live whisper segments. See plan for rationale.
    const val MODEL_TRANSCRIBE_DIARIZE = "gpt-4o-transcribe-diarize"

    // In-meeting incremental diarization window. Each window is an independent file sent to
    // the diarize model; anonymous speaker codes do NOT carry across windows — only the ≤4
    // known-voice references keep a stable real name across windows.
    const val DIARIZE_WINDOW_MS = 5 * 60_000L        // ~5-minute windows
    const val DIARIZE_WINDOW_OVERLAP_MS = 15_000L    // small overlap to avoid cutting mid-turn

    // OpenAI hard limit: at most 4 known-speaker references, each 2–10s of audio.
    const val DIARIZE_MAX_KNOWN_SPEAKERS = 4
    const val VOICE_SAMPLE_MIN_MS = 2_000L
    const val VOICE_SAMPLE_MAX_MS = 10_000L

    // Prefix for anonymous diarized speakers that could NOT be mapped to a known voice.
    // e.g. the model's "speaker A" becomes label "发言人A" in our segments.
    const val SPEAKER_LABEL_ANON_PREFIX = "发言人"

    // On-disk voice-reference clips, promoted to a named sample when the user assigns a name.
    const val VOICE_SAMPLE_DIR = "voice_samples"
}
