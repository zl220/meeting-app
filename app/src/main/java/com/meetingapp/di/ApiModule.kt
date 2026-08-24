package com.meetingapp.di

import android.content.Context
import com.meetingapp.api.AskAiApi
import com.meetingapp.api.SaveMinutesApi
import com.meetingapp.api.TranscribeApi
import com.meetingapp.api.impl.DriveMinutesApi
import com.meetingapp.api.openai.OpenAiAskApi
import com.meetingapp.api.openai.OpenAiService
import com.meetingapp.api.openai.OpenAiTranscribeApi
import com.meetingapp.api.openai.OpenAiTtsPlayer
import com.meetingapp.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    // Expose the Flow so implementations can read the current key on each call
    @Provides
    @Named("openai_api_key_flow")
    fun provideApiKeyFlow(settingsRepo: SettingsRepository): Flow<String> = settingsRepo.apiKey

    @Provides
    @Named("cache_dir")
    fun provideCacheDir(@ApplicationContext ctx: Context): File = ctx.cacheDir

    @Provides
    @Singleton
    fun provideTranscribeApi(
        service: OpenAiService,
        @Named("openai_api_key_flow") apiKeyFlow: Flow<String>
    ): TranscribeApi = OpenAiTranscribeApi(service, apiKeyFlow)

    @Provides
    @Singleton
    fun provideAskAiApi(
        service: OpenAiService,
        @Named("openai_api_key_flow") apiKeyFlow: Flow<String>
    ): AskAiApi = OpenAiAskApi(service, apiKeyFlow)

    @Provides
    @Singleton
    fun provideTtsPlayer(
        service: OpenAiService,
        @Named("openai_api_key_flow") apiKeyFlow: Flow<String>,
        @Named("cache_dir") cacheDir: File,
        @ApplicationContext ctx: Context
    ): OpenAiTtsPlayer = OpenAiTtsPlayer(service, apiKeyFlow, cacheDir, ctx)

    @Provides
    @Singleton
    fun provideSaveMinutesApi(@ApplicationContext ctx: Context): SaveMinutesApi =
        DriveMinutesApi(ctx)
}
