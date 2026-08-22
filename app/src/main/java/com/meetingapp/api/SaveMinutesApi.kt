package com.meetingapp.api

import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Minutes
import com.meetingapp.data.db.entity.Participant

interface SaveMinutesApi {
    suspend fun saveToDrive(meeting: Meeting, minutes: Minutes): String
    suspend fun sendEmail(
        meeting: Meeting,
        minutes: Minutes,
        participants: List<Participant>
    )
}
