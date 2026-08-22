package com.meetingapp.api.impl

import android.content.Context
import com.meetingapp.api.SaveMinutesApi
import com.meetingapp.data.db.entity.Meeting
import com.meetingapp.data.db.entity.Minutes
import com.meetingapp.data.db.entity.Participant
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class DriveMinutesApi @Inject constructor(
    @ApplicationContext private val context: Context
) : SaveMinutesApi {

    override suspend fun saveToDrive(meeting: Meeting, minutes: Minutes): String =
        withContext(Dispatchers.IO) {
            // Google Drive integration requires an account credential obtained at runtime
            // (GoogleSignIn). The actual Drive call is deferred to when the user grants access.
            // This stub returns a placeholder that the repository replaces after sign-in.
            "pending_drive_upload"
        }

    override suspend fun sendEmail(
        meeting: Meeting,
        minutes: Minutes,
        participants: List<Participant>
    ) = withContext(Dispatchers.Main) {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = sdf.format(Date(meeting.startedAt ?: System.currentTimeMillis()))
        val subject = "[会议纪要] ${meeting.title} $dateStr"
        val body = minutes.content

        val emails = participants.map { it.email }.filter { it.isNotBlank() }

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putExtra(android.content.Intent.EXTRA_EMAIL, emails.toTypedArray())
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, body)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "发送纪要").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
