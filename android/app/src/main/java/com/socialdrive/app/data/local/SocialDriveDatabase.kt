package com.socialdrive.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordingEntity::class,
        DraftEntity::class,
        UploadQueueEntity::class,
        RoutePrivacyEntity::class,
        NormalizedRoutePointEntity::class,
        PublishedDeleteRequestEntity::class,
        PublishConflictEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class SocialDriveDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun draftDao(): DraftDao
    abstract fun uploadQueueDao(): UploadQueueDao
    abstract fun routePrivacyDao(): RoutePrivacyDao
    abstract fun publishedDeleteDao(): PublishedDeleteDao
    abstract fun publishConflictDao(): PublishConflictDao

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

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS route_privacy_state (
                    id INTEGER NOT NULL,
                    consentState TEXT NOT NULL,
                    routeSource TEXT NOT NULL,
                    unavailableReason TEXT,
                    updatedAtEpochMs INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
            """.trimIndent())
            database.execSQL("""
                INSERT OR IGNORE INTO route_privacy_state
                    (id, consentState, routeSource, unavailableReason, updatedAtEpochMs)
                VALUES (1, 'NotAsked', 'DemoPreset', NULL, 0)
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS normalized_route_points (
                    recordingId TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    horizontal REAL NOT NULL,
                    vertical REAL NOT NULL,
                    publicationState TEXT NOT NULL,
                    PRIMARY KEY(recordingId, sequence)
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS published_delete_requests (
                    draftId TEXT NOT NULL,
                    state TEXT NOT NULL,
                    requestedAtEpochMs INTEGER NOT NULL,
                    PRIMARY KEY(draftId)
                )
            """.trimIndent())
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS publish_conflicts (
                    draftId TEXT NOT NULL,
                    clientRequestId TEXT NOT NULL,
                    remoteDriveId TEXT NOT NULL,
                    attemptedVersion INTEGER NOT NULL,
                    latestVersion INTEGER,
                    latestStatus TEXT,
                    latestTitle TEXT,
                    latestSummary TEXT,
                    state TEXT NOT NULL,
                    replayCount INTEGER NOT NULL,
                    fetchGeneration INTEGER NOT NULL,
                    detectedAtEpochMs INTEGER NOT NULL,
                    fetchedAtEpochMs INTEGER,
                    resolvedAtEpochMs INTEGER,
                    lastFetchErrorCode TEXT,
                    PRIMARY KEY(draftId)
                )
            """.trimIndent())
            database.execSQL("""
                INSERT OR IGNORE INTO publish_conflicts (
                    draftId, clientRequestId, remoteDriveId, attemptedVersion, latestVersion, latestStatus,
                    latestTitle, latestSummary, state, replayCount, fetchGeneration, detectedAtEpochMs,
                    fetchedAtEpochMs, resolvedAtEpochMs, lastFetchErrorCode
                )
                SELECT q.draftId, d.clientRequestId, COALESCE(d.remoteDriveId, ''), COALESCE(d.remoteVersion, 0),
                    NULL, NULL, NULL, NULL,
                    CASE WHEN d.remoteDriveId IS NULL OR d.remoteVersion IS NULL
                              OR d.clientRequestId != q.clientRequestId
                         THEN 'UNREVIEWABLE' ELSE 'DETECTED' END,
                    0, 0, q.updatedAtEpochMs, NULL, NULL,
                    CASE WHEN d.remoteDriveId IS NULL OR d.remoteVersion IS NULL
                              OR d.clientRequestId != q.clientRequestId
                         THEN 'IDENTITY_UNAVAILABLE' ELSE NULL END
                FROM upload_queue q JOIN drive_drafts d ON d.id = q.draftId
                WHERE q.stage = 'PUBLISH' AND q.status = 'FAILED'
                  AND q.lastErrorCode = 'VERSION_CONFLICT'
            """.trimIndent())
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS published_delete_requests_v6 (
                    draftId TEXT NOT NULL, clientRequestId TEXT NOT NULL,
                    remoteDriveId TEXT NOT NULL, idempotencyKeyHash TEXT NOT NULL,
                    state TEXT NOT NULL, attempts INTEGER NOT NULL,
                    nextAttemptAtEpochMs INTEGER NOT NULL, lastErrorCode TEXT,
                    requestedAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL,
                    confirmedAtEpochMs INTEGER, PRIMARY KEY(draftId)
                )
            """.trimIndent())
            database.execSQL("""
                INSERT INTO published_delete_requests_v6 (
                    draftId, clientRequestId, remoteDriveId, idempotencyKeyHash, state,
                    attempts, nextAttemptAtEpochMs, lastErrorCode, requestedAtEpochMs,
                    updatedAtEpochMs, confirmedAtEpochMs
                )
                SELECT draftId, '', '', '', 'LEGACY_UNREVIEWABLE', 0, 0,
                    'IDENTITY_UNAVAILABLE', requestedAtEpochMs, requestedAtEpochMs, NULL
                FROM published_delete_requests
            """.trimIndent())
            database.execSQL("DROP TABLE published_delete_requests")
            database.execSQL("ALTER TABLE published_delete_requests_v6 RENAME TO published_delete_requests")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE recording_sessions ADD COLUMN distanceMeters INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE route_privacy_state ADD COLUMN consentPolicyVersion TEXT")
            database.execSQL("""
                UPDATE route_privacy_state
                SET consentState = 'NotAsked', routeSource = 'DemoPreset'
                WHERE consentState = 'Granted'
            """.trimIndent())
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
    )
}
