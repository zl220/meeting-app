package com.meetingapp.api.openai

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface OpenAiService {

    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        @Part("language") language: RequestBody,
        @Part("prompt") prompt: RequestBody?,
        @Part("keywords") keywords: RequestBody?
    ): TranscriptionResponse

    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse

    @POST("audio/speech")
    suspend fun tts(
        @Header("Authorization") auth: String,
        @Body request: TtsRequest
    ): ResponseBody
}
