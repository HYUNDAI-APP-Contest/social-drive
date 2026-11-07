package com.socialdrive.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.data.PersistentCloudDriveFeedRepository
import com.socialdrive.app.data.local.SocialDriveDatabase
import com.socialdrive.app.data.remote.ApiHttpTransport
import com.socialdrive.app.data.remote.AuthSessionProvider
import com.socialdrive.app.data.remote.BearerToken
import com.socialdrive.app.data.remote.CloudDriveClient
import com.socialdrive.app.data.remote.CloudDriveClientConfig
import com.socialdrive.app.data.remote.HttpRequest
import com.socialdrive.app.data.remote.HttpResponse
import com.socialdrive.app.data.remote.ObjectUploadTransport
import com.socialdrive.app.domain.ContentState
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveFeedRepositoryTest {
    private lateinit var database: SocialDriveDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SocialDriveDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun successfulRefreshPersistsOnlyPublicMetadata() = runTest {
        val cursor = "next_cursor_123456789012345678901234"
        val transport = QueueTransport().apply {
            responses += feedResponse(nextCursor = cursor)
            responses += feedResponse(id = "drive-public-0002", nextCursor = null)
        }
        val repository = PersistentCloudDriveFeedRepository(database, { client(transport) }, now = { 123L })

        repository.refresh()

        val state = repository.state.value as ContentState.Content
        assertEquals(listOf("cloud-drive-public-0001", "cloud-drive-public-0002"), state.value.map { it.id })
        assertEquals("공개 드라이브", state.value.first().title)
        val cached = database.cachedFeedDao().findAll()
        assertEquals(2, cached.size)
        assertTrue(cached.all { it.cachedAtEpochMs == 123L })
        assertTrue(transport.requests.last().uri.rawQuery!!.contains("cursor=$cursor"))
        val columns = database.openHelper.readableDatabase
            .query("PRAGMA table_info(cached_feed_drives)")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }
        assertFalse(columns.any { it.contains("url", ignoreCase = true) })
        assertFalse(columns.any { it.contains("route", ignoreCase = true) })
    }

    @Test
    fun failedRefreshUsesLastValidatedCache() = runTest {
        val transport = QueueTransport().apply { responses += feedResponse() }
        var current = client(transport)
        val repository = PersistentCloudDriveFeedRepository(database, { current })
        repository.refresh()
        current = client(ThrowingTransport)

        repository.refresh()

        val state = repository.state.value as ContentState.Offline
        assertEquals("cloud-drive-public-0001", state.cached.single().id)
    }

    @Test
    fun detailRefreshesOneExpiredLinkAndKeepsRouteInMemory() = runTest {
        val api = QueueTransport().apply {
            responses += detailResponse("old-route")
            responses += detailResponse("fresh-route", version = 3)
        }
        val objects = QueueObjectTransport().apply {
            responses += HttpResponse(403)
            responses += HttpResponse(
                200,
                body = """{"schemaVersion":1,"path":[{"horizontal":0.1,"vertical":0.8,"progress":0},{"horizontal":0.9,"vertical":0.2,"progress":1}],"highlights":[]}""".toByteArray(),
            )
            responses += HttpResponse(
                200,
                body = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
            )
        }
        val repository = PersistentCloudDriveFeedRepository(database, { client(api, objects) })

        val detail = repository.detail("cloud-drive-public-0001")

        assertEquals(2, detail?.route?.size)
        assertEquals(2, api.requests.size)
        assertEquals(3, objects.requests.size)
        assertTrue(objects.requests.all { request -> request.headers.keys.none { it.equals("authorization", true) } })
        assertEquals(0, database.cachedFeedDao().findAll().size)
        assertEquals("EphemeralCoverImage([REDACTED])", detail?.coverImage.toString())
    }

    private fun client(
        api: ApiHttpTransport,
        objects: ObjectUploadTransport = ObjectUploadTransport { HttpResponse(500) },
    ) = CloudDriveClient(
        config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
        auth = AuthSessionProvider { BearerToken.inMemory("test-token") },
        apiTransport = api,
        objectTransport = objects,
    )

    private fun feedResponse(
        id: String = "drive-public-0001",
        nextCursor: String? = null,
    ) = HttpResponse(
        200,
        body = """{"items":[{"id":"$id","status":"PUBLISHED","version":2,"title":"공개 드라이브","summary":"검증된 공개 요약","distanceMeters":128400,"durationSeconds":9360,"highlightCount":4,"createdAt":"2026-11-01T10:00:00Z","publishedAt":"2026-11-01T10:05:00Z","coverUrl":"https://upload.example.test/private-cover"}],"nextCursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}""".toByteArray(),
    )

    private fun detailResponse(routeName: String, version: Int = 2) = HttpResponse(
        200,
        body = """{"drive":{"id":"drive-public-0001","status":"PUBLISHED","version":$version,"title":"공개 드라이브","summary":"검증된 공개 요약","distanceMeters":128400,"durationSeconds":9360,"highlightCount":4,"createdAt":"2026-11-01T10:00:00Z","publishedAt":"2026-11-01T10:05:00Z","coverUrl":"https://upload.example.test/private-cover","routeUrl":"https://upload.example.test/$routeName","routeSchemaVersion":1}}""".toByteArray(),
    )

    private class QueueTransport : ApiHttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val responses = ArrayDeque<HttpResponse>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class QueueObjectTransport : ObjectUploadTransport {
        val requests = mutableListOf<HttpRequest>()
        val responses = ArrayDeque<HttpResponse>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private object ThrowingTransport : ApiHttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse = error("offline")
    }
}
