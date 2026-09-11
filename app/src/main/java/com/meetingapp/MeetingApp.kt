package com.meetingapp

import android.app.Application
import com.meetingapp.service.AudioCleaner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MeetingApp : Application() {

    @Inject lateinit var audioCleaner: AudioCleaner

    override fun onCreate() {
        super.onCreate()
        // Purge meeting audio past the retention window so recordings don't grow unbounded.
        audioCleaner.sweep()
    }
}
