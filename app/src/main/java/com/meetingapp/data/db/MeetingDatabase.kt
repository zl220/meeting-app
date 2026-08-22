package com.meetingapp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.meetingapp.data.db.dao.*
import com.meetingapp.data.db.entity.*

@Database(
    entities = [Participant::class, Meeting::class, MeetingParticipant::class,
        Segment::class, Minutes::class, AudioChunk::class],
    version = 1,
    exportSchema = false
)
abstract class MeetingDatabase : RoomDatabase() {
    abstract fun participantDao(): ParticipantDao
    abstract fun meetingDao(): MeetingDao
    abstract fun segmentDao(): SegmentDao
    abstract fun minutesDao(): MinutesDao
    abstract fun audioChunkDao(): AudioChunkDao
}
