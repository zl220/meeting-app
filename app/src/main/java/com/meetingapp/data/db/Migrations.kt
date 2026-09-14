package com.meetingapp.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. Every version bump ships a real migration here (registered in
 * AppModule via .addMigrations) so stored meetings/minutes/recordings survive upgrades.
 */
object Migrations {

    /**
     * v3 → v4: add the `voice_samples` table backing the diarization voice library.
     * One sample per participant (unique index on participantId), FK-cascaded to participants.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `voice_samples` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `participantId` INTEGER NOT NULL,
                    `filePath` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `capturedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`participantId`) REFERENCES `participants`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_voice_samples_participantId` " +
                    "ON `voice_samples` (`participantId`)"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_3_4)
}
