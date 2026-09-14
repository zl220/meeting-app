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

    /**
     * v4 → v5: grow the voice library.
     *  - `voice_samples`: add `qualityScore`; allow multiple samples per participant (swap the
     *    UNIQUE index on participantId for a plain one).
     *  - add `pending_voice_samples` for unnamed in-meeting clips (kept up to the retention window).
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // qualityScore on existing named samples (default 0 = unknown/lowest).
            db.execSQL("ALTER TABLE `voice_samples` ADD COLUMN `qualityScore` REAL NOT NULL DEFAULT 0")
            // Drop the unique index and recreate as non-unique so a participant may keep many clips.
            db.execSQL("DROP INDEX IF EXISTS `index_voice_samples_participantId`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_voice_samples_participantId` " +
                    "ON `voice_samples` (`participantId`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_voice_samples` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `meetingId` INTEGER NOT NULL,
                    `speakerLabel` TEXT NOT NULL,
                    `filePath` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `qualityScore` REAL NOT NULL,
                    `capturedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`meetingId`) REFERENCES `meetings`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_voice_samples_meetingId_speakerLabel` " +
                    "ON `pending_voice_samples` (`meetingId`, `speakerLabel`)"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_3_4, MIGRATION_4_5)
}
