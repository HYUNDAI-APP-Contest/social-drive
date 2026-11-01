package com.socialdrive.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordingEntity::class, DraftEntity::class, UploadQueueEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class SocialDriveDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun draftDao(): DraftDao
    abstract fun uploadQueueDao(): UploadQueueDao

    companion object {
        fun create(context: Context): SocialDriveDatabase = Room.databaseBuilder(
            context.applicationContext,
            SocialDriveDatabase::class.java,
            "social-drive.db",
        ).addMigrations(*RoomMigrations.ALL).build()
    }
}

object RoomMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE upload_queue ADD COLUMN lastErrorCode TEXT")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE drive_drafts ADD COLUMN remoteVersion INTEGER")
            database.execSQL("ALTER TABLE upload_queue ADD COLUMN uploadRewindCount INTEGER NOT NULL DEFAULT 0")
            database.execSQL("""
                UPDATE upload_queue
                SET stage = 'CREATE', status = 'QUEUED', lastErrorCode = 'REMOTE_VERSION_REQUIRED'
                WHERE draftId IN (
                    SELECT id FROM drive_drafts
                    WHERE remoteDriveId IS NOT NULL AND remoteVersion IS NULL
                )
                  AND status IN ('QUEUED', 'RUNNING', 'PAUSED_MOTION', 'FAILED')
                  AND stage NOT IN ('COMPLETE', 'PUBLISHED', 'DELETED')
            """.trimIndent())
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
