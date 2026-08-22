package com.meetingapp.di

import android.content.Context
import androidx.room.Room
import com.meetingapp.api.openai.OpenAiService
import com.meetingapp.data.db.MeetingDatabase
import com.meetingapp.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): MeetingDatabase =
        Room.databaseBuilder(ctx, MeetingDatabase::class.java, "meeting_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideParticipantDao(db: MeetingDatabase) = db.participantDao()
    @Provides fun provideMeetingDao(db: MeetingDatabase) = db.meetingDao()
    @Provides fun provideSegmentDao(db: MeetingDatabase) = db.segmentDao()
    @Provides fun provideMinutesDao(db: MeetingDatabase) = db.minutesDao()
    @Provides fun provideAudioChunkDao(db: MeetingDatabase) = db.audioChunkDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideOpenAiService(client: OkHttpClient): OpenAiService =
        Retrofit.Builder()
            .baseUrl(Constants.OPENAI_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiService::class.java)
}
