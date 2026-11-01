package com.socialdrive.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.socialdrive.app.data.local.RoomMigrations
import com.socialdrive.app.data.local.SocialDriveDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    companion object { private const val DATABASE = "migration-test" }
}
