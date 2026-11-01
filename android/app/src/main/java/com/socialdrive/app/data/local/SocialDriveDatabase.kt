package com.socialdrive.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordingEntity::class, DraftEntity::class, UploadQueueEntity::class],
    version = 2,
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
        ).addMigrations(RoomMigrations.MIGRATION_1_2).build()
    }
}

object RoomMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE upload_queue ADD COLUMN lastErrorCode TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
