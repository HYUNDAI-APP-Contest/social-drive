package com.socialdrive.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.socialdrive.app.data.local.RoomMigrations
import com.socialdrive.app.data.local.SocialDriveDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.test.runTest

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SocialDriveDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateOneToThreePreservesRowsAndAddsFrozenVersionAndBoundedRewindState() {
        helper.createDatabase(DATABASE, 1).apply {
            execSQL("INSERT INTO recording_sessions VALUES ('recording-1','DRAFTED',100,200,3)")
            execSQL("INSERT INTO drive_drafts VALUES ('draft-1','recording-1','drive-request-1','title','description','route-memory-default','local-recording://recording-1','QUEUED','remote-legacy',100,200,NULL)")
            execSQL("INSERT INTO upload_queue VALUES ('draft-1','drive-request-1','UPLOAD','QUEUED',1,200,200)")
            close()
        }

        helper.runMigrationsAndValidate(DATABASE, 3, true, *RoomMigrations.ALL).use { database ->
            database.query("SELECT state, highlightCount FROM recording_sessions WHERE id = 'recording-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("DRAFTED", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
            }
            database.query("SELECT clientRequestId, originalContentRef, remoteDriveId, remoteVersion FROM drive_drafts WHERE id = 'draft-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("drive-request-1", cursor.getString(0))
                assertEquals("local-recording://recording-1", cursor.getString(1))
                assertEquals("remote-legacy", cursor.getString(2))
                assertNull(cursor.getString(3))
            }
            database.query("SELECT stage, status, lastErrorCode, uploadRewindCount FROM upload_queue WHERE draftId = 'draft-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("CREATE", cursor.getString(0))
                assertEquals("QUEUED", cursor.getString(1))
                assertEquals("REMOTE_VERSION_REQUIRED", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
        }
    }

    @Test
    fun migrateTwoToThreeRepairsFailedUploadAndPublishWithoutRewindingTerminalRows() = runTest {
        helper.createDatabase(FAILED_DATABASE, 2).apply {
            for ((id, state) in listOf(
                "failed-upload" to "QUEUED",
                "failed-publish" to "QUEUED",
                "published" to "PUBLISHED",
                "deleted" to "DELETED",
                "complete-failed" to "QUEUED",
            )) {
                execSQL(
                    """INSERT INTO drive_drafts
                        (id, recordingId, clientRequestId, title, description, coverPreset,
                         originalContentRef, state, remoteDriveId, createdAtEpochMs,
                         updatedAtEpochMs, publishedAtEpochMs)
                       VALUES ('$id', 'recording-$id', 'request-$id', 'title', 'description',
                         'route-memory-default', 'local-recording://$id', '$state', 'remote-$id',
                         100, 200, ${if (state == "PUBLISHED") "200" else "NULL"})""".trimIndent(),
                )
            }
            execSQL("INSERT INTO upload_queue VALUES ('failed-upload','request-failed-upload','UPLOAD','FAILED',3,200,'OFFLINE',200)")
            execSQL("INSERT INTO upload_queue VALUES ('failed-publish','request-failed-publish','PUBLISH','FAILED',4,200,'SERVER_BUSY',200)")
            execSQL("INSERT INTO upload_queue VALUES ('published','request-published','COMPLETE','PUBLISHED',5,0,NULL,200)")
            execSQL("INSERT INTO upload_queue VALUES ('deleted','request-deleted','COMPLETE','DELETED',5,0,NULL,200)")
            execSQL("INSERT INTO upload_queue VALUES ('complete-failed','request-complete-failed','COMPLETE','FAILED',5,0,'SERVER_BUSY',200)")
            close()
        }

        helper.runMigrationsAndValidate(FAILED_DATABASE, 3, true, RoomMigrations.MIGRATION_2_3).use { database ->
            for (id in listOf("failed-upload", "failed-publish")) {
                database.query("SELECT stage, status, lastErrorCode, uploadRewindCount FROM upload_queue WHERE draftId = '$id'").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("CREATE", cursor.getString(0))
                    assertEquals("QUEUED", cursor.getString(1))
                    assertEquals("REMOTE_VERSION_REQUIRED", cursor.getString(2))
                    assertEquals(0, cursor.getInt(3))
                }
            }
            database.query("SELECT stage, status FROM upload_queue WHERE draftId = 'published'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("COMPLETE", cursor.getString(0))
                assertEquals("PUBLISHED", cursor.getString(1))
            }
            database.query("SELECT stage, status FROM upload_queue WHERE draftId = 'deleted'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("COMPLETE", cursor.getString(0))
                assertEquals("DELETED", cursor.getString(1))
            }
            database.query("SELECT stage, status, lastErrorCode FROM upload_queue WHERE draftId = 'complete-failed'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("COMPLETE", cursor.getString(0))
                assertEquals("FAILED", cursor.getString(1))
                assertEquals("SERVER_BUSY", cursor.getString(2))
            }
        }

        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SocialDriveDatabase::class.java,
            FAILED_DATABASE,
        ).addMigrations(*RoomMigrations.ALL).allowMainThreadQueries().build()
        try {
            val queue = requireNotNull(room.uploadQueueDao().find("failed-upload"))
            assertEquals(1, room.uploadQueueDao().claimStage(queue.draftId, "CREATE", "QUEUED", 3, 4, 300))
            val draft = requireNotNull(room.draftDao().find(queue.draftId))
            assertEquals(
                1,
                room.draftDao().applyCreateResultCas(
                    draft.id, draft.state, draft.remoteDriveId, null, "remote-failed-upload", 6, 301,
                ),
            )
            assertEquals(1, room.uploadQueueDao().completeCreateStage(queue.draftId, 4, 301))
            assertEquals(6, room.draftDao().find(queue.draftId)?.remoteVersion)
            assertEquals("UPLOAD", room.uploadQueueDao().find(queue.draftId)?.stage)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrateThreeToFourAddsPrivacyStateWithoutChangingPublishRows() {
        helper.createDatabase(PRIVACY_DATABASE, 3).apply {
            execSQL("INSERT INTO drive_drafts VALUES ('draft-privacy','recording-privacy','request-privacy','title','description','route-memory-default','local-recording://privacy','QUEUED','remote-privacy',7,100,200,NULL)")
            execSQL("INSERT INTO upload_queue VALUES ('draft-privacy','request-privacy','UPLOAD','QUEUED',2,200,NULL,0,200)")
            close()
        }

        helper.runMigrationsAndValidate(PRIVACY_DATABASE, 4, true, RoomMigrations.MIGRATION_3_4).use { database ->
            database.query("SELECT consentState, routeSource, unavailableReason FROM route_privacy_state WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("NotAsked", cursor.getString(0))
                assertEquals("DemoPreset", cursor.getString(1))
                assertNull(cursor.getString(2))
            }
            database.query("SELECT stage, status, attempts FROM upload_queue WHERE draftId = 'draft-privacy'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("UPLOAD", cursor.getString(0))
                assertEquals("QUEUED", cursor.getString(1))
                assertEquals(2, cursor.getInt(2))
            }
            for (table in listOf("normalized_route_points", "published_delete_requests")) {
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun migrateSevenToEightInvalidatesUnversionedLocationConsent() {
        helper.createDatabase(PRIVACY_POLICY_DATABASE, 7).apply {
            execSQL("UPDATE route_privacy_state SET consentState='Granted', routeSource='LiveAvailable', updatedAtEpochMs=123 WHERE id=1")
            close()
        }

        helper.runMigrationsAndValidate(PRIVACY_POLICY_DATABASE, 8, true, RoomMigrations.MIGRATION_7_8).use { database ->
            database.query("SELECT consentState, consentPolicyVersion, routeSource FROM route_privacy_state WHERE id=1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("NotAsked", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals("DemoPreset", cursor.getString(2))
            }
        }
    }

    @Test
    fun migrateFourToFiveBackfillsReviewableAndUnreviewableConflictsWithoutChangingDrafts() {
        helper.createDatabase(CONFLICT_DATABASE, 4).apply {
            execSQL("INSERT INTO drive_drafts VALUES ('reviewable','rec-1','request-1','local title','local body','route-memory-default','local-recording://rec-1','QUEUED','drive-remote-1',4,100,200,NULL)")
            execSQL("INSERT INTO upload_queue VALUES ('reviewable','request-1','PUBLISH','FAILED',2,200,'VERSION_CONFLICT',0,200)")
            execSQL("INSERT INTO drive_drafts VALUES ('mismatch','rec-2','request-2','keep title','keep body','route-memory-default','local-recording://rec-2','QUEUED','drive-remote-2',7,100,200,NULL)")
            execSQL("INSERT INTO upload_queue VALUES ('mismatch','request-other','PUBLISH','FAILED',3,200,'VERSION_CONFLICT',0,200)")
            close()
        }

        helper.runMigrationsAndValidate(CONFLICT_DATABASE, 5, true, RoomMigrations.MIGRATION_4_5).use { database ->
            database.query("SELECT clientRequestId, remoteDriveId, attemptedVersion, state, replayCount, fetchGeneration FROM publish_conflicts WHERE draftId='reviewable'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("request-1", cursor.getString(0))
                assertEquals("drive-remote-1", cursor.getString(1))
                assertEquals(4, cursor.getInt(2))
                assertEquals("DETECTED", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(0, cursor.getInt(5))
            }
            database.query("SELECT state, lastFetchErrorCode FROM publish_conflicts WHERE draftId='mismatch'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("UNREVIEWABLE", cursor.getString(0))
                assertEquals("IDENTITY_UNAVAILABLE", cursor.getString(1))
            }
            database.query("SELECT title, description, originalContentRef, remoteVersion FROM drive_drafts WHERE id='reviewable'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("local title", cursor.getString(0))
                assertEquals("local body", cursor.getString(1))
                assertEquals("local-recording://rec-1", cursor.getString(2))
                assertEquals(4, cursor.getInt(3))
            }
        }
    }

    @Test
    fun migrateFiveToSixMakesLegacyDeletePendingFailClosed() {
        helper.createDatabase(DELETE_DATABASE, 5).apply {
            execSQL("INSERT INTO published_delete_requests VALUES ('draft-legacy-delete','DELETE_PENDING',900)")
            close()
        }

        helper.runMigrationsAndValidate(DELETE_DATABASE, 6, true, RoomMigrations.MIGRATION_5_6).use { database ->
            database.query("""
                SELECT state, clientRequestId, remoteDriveId, idempotencyKeyHash, attempts,
                       nextAttemptAtEpochMs, lastErrorCode, requestedAtEpochMs, updatedAtEpochMs,
                       confirmedAtEpochMs
                FROM published_delete_requests WHERE draftId='draft-legacy-delete'
            """.trimIndent()).use { cursor ->
                cursor.moveToFirst()
                assertEquals("LEGACY_UNREVIEWABLE", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals(0, cursor.getLong(5))
                assertEquals("IDENTITY_UNAVAILABLE", cursor.getString(6))
                assertEquals(900L, cursor.getLong(7))
                assertEquals(900L, cursor.getLong(8))
                assertNull(cursor.getString(9))
            }
        }
    }

    @Test
    fun migrateSixToSevenAddsPrivacySafeAggregateDistance() {
        helper.createDatabase(DISTANCE_DATABASE, 6).apply {
            execSQL("INSERT INTO recording_sessions VALUES ('recording-distance','DRAFTED',100,200,3)")
            close()
        }

        helper.runMigrationsAndValidate(
            DISTANCE_DATABASE,
            7,
            true,
            RoomMigrations.MIGRATION_6_7,
        ).use { database ->
            database.query(
                "SELECT state, highlightCount, distanceMeters FROM recording_sessions WHERE id='recording-distance'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("DRAFTED", cursor.getString(0))
                assertEquals(3, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
        }
    }

    companion object {
        private const val DATABASE = "migration-test"
        private const val FAILED_DATABASE = "migration-failed-repair-test"
        private const val PRIVACY_DATABASE = "migration-route-privacy-test"
        private const val PRIVACY_POLICY_DATABASE = "migration-route-policy-test"
        private const val CONFLICT_DATABASE = "migration-conflict-review-test"
        private const val DELETE_DATABASE = "migration-delete-lifecycle-test"
        private const val DISTANCE_DATABASE = "migration-distance-test"
    }
}
