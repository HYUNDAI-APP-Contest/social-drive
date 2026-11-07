package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.socialdrive.app.domain.DeleteFailureCode
import com.socialdrive.app.domain.NormalizedRoutePoint

class CloudDriveClientTest {
    @Test
    fun `AWS 게시 어댑터는 합성 데모 경로를 생성 전에 거부한다`() = runBlocking {
        val harness = Harness()
        val draft = testDraft().copy(routePoints = emptyList())

        val failure = assertFailsWith<RemoteDriveException> {
            CloudRemoteDriveApi(harness.client, "2026-11-01").createDrive(draft.clientRequestId, draft)
        }

        assertEquals(RemoteFailureCode.REJECTED, failure.code)
        assertFalse(failure.retryable)
        assertTrue(harness.api.requests.isEmpty())
    }

    @Test
    fun `동의 철회 경합으로 합성된 경로 명령은 AWS 업로드 전에 거부한다`() = runBlocking {
        val harness = Harness()
        val commands = testDraft().copy(routePoints = emptyList()).assetUploads()

        val failure = assertFailsWith<RemoteDriveException> {
            CloudRemoteDriveApi(harness.client, "2026-11-01").uploadAssets("drive-remote-0001", commands)
        }

        assertEquals(RemoteFailureCode.REJECTED, failure.code)
        assertTrue(harness.api.requests.isEmpty())
        assertTrue(harness.objects.requests.isEmpty())
    }

    @Test
    fun `토큰 갱신 시간 초과는 로그인 해제가 아닌 재시도 가능한 장애다`() = runBlocking {
        val api = FakeTransport()
        val client = CloudDriveClient(
            config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
            auth = AuthSessionProvider { throw AuthSessionTemporarilyUnavailableException() },
            apiTransport = api,
            objectTransport = FakeObjectTransport(),
        )

        val failure = assertFailsWith<CloudDriveException> { client.feed() }
        assertEquals(CloudFailureKind.RETRYABLE, failure.kind)
        assertEquals("AUTH_SESSION_TEMPORARILY_UNAVAILABLE", failure.code)
        assertTrue(failure.retryable)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `토큰 갱신 시간 초과는 게시 작업 어댑터까지 재시도 가능 상태를 유지한다`() = runBlocking {
        val api = FakeTransport()
        val client = CloudDriveClient(
            config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
            auth = AuthSessionProvider { throw AuthSessionTemporarilyUnavailableException() },
            apiTransport = api,
            objectTransport = FakeObjectTransport(),
        )
        val draft = testDraft()

        val failure = assertFailsWith<RemoteDriveException> {
            CloudRemoteDriveApi(client, "2026-11-01").createDrive(draft.clientRequestId, draft)
        }

        assertEquals(RemoteFailureCode.SERVER_BUSY, failure.code)
        assertTrue(failure.retryable)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `SigV4 contract rejects malformed scope expiry signature and unsigned required headers`() = runBlocking {
        val mutations = listOf<(String) -> String>(
            { it.replace("AWS4-HMAC-SHA256", "AWS4-ECDSA-P256-SHA256") },
            { it.replace("%2Fs3%2Faws4_request", "%2Fec2%2Faws4_request") },
            { it.replace("X-Amz-Date=20261101T100000Z", "X-Amz-Date=20261102T100000Z") },
            { it.replace("X-Amz-Expires=600", "X-Amz-Expires=901") },
            { it.replace("a".repeat(64), "z".repeat(64)) },
            { it.replace("\"x-amz-checksum-sha256\":", "\"x-extra\":\"unsigned\",\"x-amz-checksum-sha256\":") },
        )
        mutations.forEach { mutate ->
            val harness = Harness()
            harness.api.responses += jsonResponse(200, mutate(uploadSessionResponse(harness.manifests)))
            val failure = assertFailsWith<CloudDriveException> {
                harness.client.refreshUploads("drive-remote-0001", harness.manifests)
            }
            assertEquals(CloudFailureKind.CONTRACT, failure.kind)
            assertTrue(harness.objects.requests.isEmpty())
        }
    }

    @Test
    fun `response parser enforces byte depth node and string limits`() = runBlocking {
        val harness = Harness()
        harness.api.responses += HttpResponse(200, body = ByteArray(2 * 1024 * 1024 + 1))
        val oversized = assertFailsWith<CloudDriveException> { harness.client.feed() }
        assertEquals("RESPONSE_TOO_LARGE", oversized.code)

        assertFailsWith<IllegalArgumentException> {
            JsonCodec.parse(("[".repeat(34) + "0" + "]".repeat(34)).toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            JsonCodec.parse(("[" + List(10_001) { "0" }.joinToString(",") + "]").toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            JsonCodec.parse(("\"" + "x".repeat(16_385) + "\"").toByteArray())
        }

        val cover = harness.manifests.first { it.kind == AssetKind.COVER }
        harness.objects.responses += HttpResponse(200, body = ByteArray(2 * 1024 * 1024 + 1))
        val objectOversized = assertFailsWith<CloudDriveException> {
            harness.client.uploadAsset(validInstruction(cover), cover, harness.coverBytes)
        }
        assertEquals("RESPONSE_TOO_LARGE", objectOversized.code)
    }

    @Test
    fun `response contract rejects excessive feed items and duplicate upload instructions`() = runBlocking {
        val feedHarness = Harness()
        feedHarness.api.responses += jsonResponse(
            200,
            """{"items":[${driveJson(status = "PUBLISHED")},${driveJson(status = "PUBLISHED")}],"nextCursor":null}""",
        )
        val excessiveFeed = assertFailsWith<CloudDriveException> { feedHarness.client.feed(limit = 1) }
        assertEquals("FEED_LIMIT_EXCEEDED", excessiveFeed.code)

        val uploadHarness = Harness()
        val duplicateManifests = uploadHarness.manifests + uploadHarness.manifests.first()
        uploadHarness.api.responses += jsonResponse(200, uploadSessionResponse(duplicateManifests))
        val duplicateUploads = assertFailsWith<CloudDriveException> {
            uploadHarness.client.refreshUploads("drive-remote-0001", uploadHarness.manifests)
        }
        assertEquals("INVALID_UPLOAD_SET", duplicateUploads.code)
        assertTrue(uploadHarness.objects.requests.isEmpty())
    }

    @Test
    fun `drive resources reject unsafe ids impossible ranges and unreasonable timestamps`() = runBlocking {
        val invalidDrives = listOf(
            driveJson(status = "PUBLISHED").replace("drive-remote-0001", "bad id"),
            driveJson(status = "PUBLISHED", version = 0),
            driveJson(status = "PUBLISHED").replace("\"distanceMeters\":0", "\"distanceMeters\":-1"),
            driveJson(status = "PUBLISHED").replace("\"title\":\"바닷길\"", "\"title\":\"\""),
            driveJson(status = "PUBLISHED").replace("2026-11-01T10:00:00Z", "2201-11-01T10:00:00Z"),
        )
        invalidDrives.forEach { drive ->
            val harness = Harness()
            harness.api.responses += jsonResponse(200, """{"items":[$drive],"nextCursor":null}""")
            val failure = assertFailsWith<CloudDriveException> { harness.client.feed() }
            assertEquals(CloudFailureKind.CONTRACT, failure.kind)
        }
    }

    @Test
    fun `worker adapter reuses frozen manifests refreshes upload and fetches publish version`() = runBlocking {
        val harness = Harness()
        val draft = DraftSnapshot(
            id = "local-draft-0001",
            recordingId = "recording-0001",
            clientRequestId = "drive-request-0001",
            title = "바닷길",
            description = "공개용 요약",
            coverPreset = "route-memory-default",
            originalContentRef = "local-recording://recording-0001",
            state = "QUEUED",
            highlightCount = 3,
            durationSeconds = 3_661,
            distanceMeters = 128_400,
            routePoints = listOf(
                NormalizedRoutePoint(0, .2f, .7f),
                NormalizedRoutePoint(1, .8f, .3f),
            ),
        )
        val adapter = CloudRemoteDriveApi(harness.client, "2026-11-01")
        val assets = draft.assetUploads()
        harness.api.responses += jsonResponse(201, createResponse(assets.map(AssetUploadCommand::testManifest)))
        assertEquals(RemoteDraftRef("drive-remote-0001", 1), adapter.createDrive(draft.clientRequestId, draft))
        val createBody = JsonCodec.parse(harness.api.requests.last().body!!).objectValue()
        assertEquals(3, createBody.integer("highlightCount"))
        assertEquals(3_661, createBody.integer("durationSeconds"))
        assertEquals(128_400, createBody.integer("distanceMeters"))

        harness.api.responses += jsonResponse(200, uploadSessionResponse(assets.map(AssetUploadCommand::testManifest)))
        harness.objects.responses += HttpResponse(200)
        harness.objects.responses += HttpResponse(200)
        adapter.uploadAssets("drive-remote-0001", assets)
        assertEquals(HttpMethod.POST, harness.api.requests.last().method)
        assertTrue(harness.api.requests.last().uri.path.endsWith("/drives/drive-remote-0001/uploads"))
        assertEquals(2, harness.objects.requests.size)
        assertTrue(harness.objects.requests.all { request -> request.headers.keys.none { it.equals("authorization", true) } })

        harness.api.responses += jsonResponse(200, """{"drive":${driveJson(status = "PUBLISHED", version = 8)}}""")
        adapter.publishDrive("drive-remote-0001", 7, draft.clientRequestId)
        assertEquals(7, JsonCodec.parse(harness.api.requests.last().body!!).objectValue().integer("expectedVersion"))
        assertTrue(harness.api.requests.last().uri.path.endsWith("/publish"))

        harness.api.responses += jsonResponse(200, """{"drive":${driveJson(status = "PUBLISHED", version = 8)}}""")
        assertEquals(8, adapter.publishDrive("drive-remote-0001", 7, draft.clientRequestId).version)
        assertEquals(7, JsonCodec.parse(harness.api.requests.last().body!!).objectValue().integer("expectedVersion"))

        harness.api.responses += errorResponse(409, "VERSION_CONFLICT", retryable = false)
        val conflict = assertFailsWith<RemoteDriveException> {
            adapter.publishDrive("drive-remote-0001", 7, draft.clientRequestId)
        }
        assertEquals(RemoteFailureCode.VERSION_CONFLICT, conflict.code)
        assertFalse(conflict.retryable)
    }

    @Test
    fun `expired object session refreshes full manifest once and remains bounded`() = runBlocking {
        val harness = Harness()
        val adapter = CloudRemoteDriveApi(harness.client, "2026-11-01")
        val draft = testDraft()
        val assets = draft.assetUploads()
        repeat(2) { harness.api.responses += jsonResponse(200, uploadSessionResponse(assets.map { it.testManifest() })) }
        harness.objects.responses += HttpResponse(200)
        harness.objects.responses += HttpResponse(403)
        harness.objects.responses += HttpResponse(200)
        harness.objects.responses += HttpResponse(200)

        adapter.uploadAssets("drive-remote-0001", assets)

        assertEquals(2, harness.api.requests.count { it.uri.path.endsWith("/uploads") })
        assertEquals(4, harness.objects.requests.size)

        val exhausted = Harness()
        val exhaustedAdapter = CloudRemoteDriveApi(exhausted.client, "2026-11-01")
        repeat(2) { exhausted.api.responses += jsonResponse(200, uploadSessionResponse(assets.map { it.testManifest() })) }
        exhausted.objects.responses += HttpResponse(403)
        exhausted.objects.responses += HttpResponse(403)
        val failure = assertFailsWith<RemoteDriveException> {
            exhaustedAdapter.uploadAssets("drive-remote-0001", assets)
        }
        assertEquals(RemoteFailureCode.UPLOAD_EXPIRED, failure.code)
        assertEquals(2, exhausted.api.requests.count { it.uri.path.endsWith("/uploads") })
        assertEquals(2, exhausted.objects.requests.size)
    }

    @Test
    fun `create uses a fresh bearer token and one stable request id for body and idempotency`() = runBlocking {
        val harness = Harness()
        harness.api.responses += jsonResponse(201, createResponse(harness.manifests))

        val created = harness.client.createDrive(harness.input)
        val request = harness.api.requests.single()
        val body = JsonCodec.parse(request.body!!).objectValue()

        assertEquals("drive-request-0001", request.headers["Idempotency-Key"])
        assertEquals("drive-request-0001", body.string("clientDraftId"))
        assertEquals("Bearer fresh-token-1", request.headers["Authorization"])
        assertEquals("/dev/drives", request.uri.path)
        assertEquals(setOf("COVER", "ROUTE"), body.array("assets").map { it.objectValue().string("kind") }.toSet())
        assertTrue(body.array("assets").all { it.objectValue().string("sha256").matches(Regex("[a-f0-9]{64}")) })
        assertEquals("drive-remote-0001", created.drive.id)
        assertEquals(2, created.uploads.size)

        harness.api.responses += jsonResponse(200, createResponse(harness.manifests))
        harness.client.createDrive(harness.input)
        assertEquals("Bearer fresh-token-2", harness.api.requests.last().headers["Authorization"])
    }

    @Test
    fun `refresh uses exact manifest and upload sends only signed headers to allowed host`() = runBlocking {
        val harness = Harness()
        val cover = harness.manifests.first { it.kind == AssetKind.COVER }
        assertFailsWith<IllegalArgumentException> {
            harness.client.refreshUploads("drive-remote-0001", listOf(cover))
        }
        harness.api.responses += jsonResponse(200, uploadSessionResponse(harness.manifests))

        val session = harness.client.refreshUploads("drive-remote-0001", harness.manifests)
        val apiRequest = harness.api.requests.single()
        val sentAssets = JsonCodec.parse(apiRequest.body!!).objectValue().array("assets").map { it.objectValue() }
        val sentAsset = sentAssets.single { it.string("kind") == "COVER" }
        assertEquals(setOf("COVER", "ROUTE"), sentAssets.map { it.string("kind") }.toSet())
        assertEquals(cover.sha256Hex, sentAsset.string("sha256"))
        assertEquals("image/jpeg", sentAsset.string("contentType"))

        harness.objects.responses += HttpResponse(200)
        harness.client.uploadAsset(session.uploads.single { it.kind == AssetKind.COVER }, cover, harness.coverBytes)
        val put = harness.objects.requests.single()
        assertEquals(HttpMethod.PUT, put.method)
        assertEquals("upload.example.test", put.uri.host)
        assertEquals("image/jpeg", put.headers.entries.single { it.key.equals("content-type", true) }.value)
        assertEquals(hexToBase64(cover.sha256Hex), put.headers.entries.single { it.key.equals("x-amz-checksum-sha256", true) }.value)
        assertFalse(put.headers.keys.any { it.equals("authorization", true) })
    }

    @Test
    fun `upload contract rejects foreign host header mismatch and content mismatch before object transport`() = runBlocking {
        val harness = Harness()
        val cover = harness.manifests.first { it.kind == AssetKind.COVER }
        harness.api.responses += jsonResponse(200, uploadSessionResponse(harness.manifests, host = "evil.example.test"))
        val hostError = assertFailsWith<CloudDriveException> {
            harness.client.refreshUploads("drive-remote-0001", harness.manifests)
        }
        assertEquals(CloudFailureKind.CONTRACT, hostError.kind)

        harness.api.responses += jsonResponse(200, uploadSessionResponse(harness.manifests, checksum = "wrong"))
        val headerError = assertFailsWith<CloudDriveException> {
            harness.client.refreshUploads("drive-remote-0001", harness.manifests)
        }
        assertEquals("UNSIGNED_UPLOAD_METADATA", headerError.code)

        harness.api.responses += jsonResponse(
            200,
            uploadSessionResponse(harness.manifests, signedHeaders = "host"),
        )
        val unsignedError = assertFailsWith<CloudDriveException> {
            harness.client.refreshUploads("drive-remote-0001", harness.manifests)
        }
        assertEquals("UNSIGNED_UPLOAD_HEADERS", unsignedError.code)

        val instruction = validInstruction(cover)
        val contentError = assertFailsWith<CloudDriveException> {
            harness.client.uploadAsset(instruction, cover, "not-jpeg".toByteArray())
        }
        assertEquals("UPLOAD_SIZE_MISMATCH", contentError.code)
        assertTrue(harness.objects.requests.isEmpty())

        harness.clockNow = Instant.parse("2026-11-01T10:09:31Z")
        val expired = assertFailsWith<CloudDriveException> {
            harness.client.uploadAsset(instruction, cover, harness.coverBytes)
        }
        assertEquals(CloudFailureKind.UPLOAD_EXPIRED, expired.kind)
        assertTrue(harness.objects.requests.isEmpty())
    }

    @Test
    fun `feed forwards an opaque cursor without decoding and maps detail status`() = runBlocking {
        val harness = Harness()
        val opaque = "opaque_cursor_123456789012345678901234"
        harness.api.responses += jsonResponse(
            200,
            """{"items":[${driveJson(status = "PUBLISHED", extra = ",\"coverUrl\":\"https://upload.example.test/cover?sig=secret\"")}],"nextCursor":"$opaque"}""",
        )
        val first = harness.client.feed(limit = 1)
        assertEquals(DriveStatus.PUBLISHED, first.items.single().drive.status)
        assertEquals("OpaqueFeedCursor([REDACTED])", first.nextCursor.toString())
        assertFalse(first.items.single().links.toString().contains("sig="))

        harness.api.responses += jsonResponse(200, """{"items":[],"nextCursor":null}""")
        harness.client.feed(limit = 1, cursor = first.nextCursor)
        assertTrue(harness.api.requests.last().uri.rawQuery!!.contains("cursor=$opaque"))
        assertFalse(harness.api.requests.last().uri.rawQuery!!.contains("FeedPK"))

        harness.api.responses += jsonResponse(
            200,
            """{"drive":${driveJson(extra = ",\"coverUrl\":\"https://upload.example.test/c\",\"routeUrl\":\"https://upload.example.test/r\",\"routeSchemaVersion\":1")}}""",
        )
        val detail = harness.client.getDrive("drive-remote-0001")
        assertEquals(DriveStatus.DRAFT, detail.drive.status)
        assertEquals(1, detail.routeSchemaVersion)

        harness.objects.responses += jsonResponse(
            200,
            """{"schemaVersion":1,"path":[{"horizontal":0.1,"vertical":0.8,"progress":0},{"horizontal":0.9,"vertical":0.2,"progress":1}],"highlights":[{"progress":0.5,"title":"좋은 순간"}]}""",
        )
        val route = harness.client.downloadRoute(detail)
        assertEquals(2, route.points.size)
        assertEquals(.1f, route.points.first().horizontal)
        assertEquals(HttpMethod.GET, harness.objects.requests.last().method)
        assertTrue(harness.objects.requests.last().headers.isEmpty())
        assertEquals(null, harness.objects.requests.last().body)

        harness.objects.responses += HttpResponse(200, body = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        val cover = harness.client.downloadCover(detail)
        assertEquals(4, cover.size)
        assertTrue(harness.objects.requests.last().headers.isEmpty())

        harness.objects.responses += HttpResponse(200, body = "not-an-image".toByteArray())
        val malformedCover = assertFailsWith<CloudDriveException> { harness.client.downloadCover(detail) }
        assertEquals("INVALID_COVER_IMAGE", malformedCover.code)

        harness.objects.responses += HttpResponse(403)
        val expiredRoute = assertFailsWith<CloudDriveException> { harness.client.downloadRoute(detail) }
        assertEquals("READ_LINK_EXPIRED", expiredRoute.code)
        assertTrue(expiredRoute.retryable)

        harness.objects.responses += jsonResponse(
            200,
            """{"schemaVersion":1,"path":[{"horizontal":37.5,"vertical":0.8},{"horizontal":0.9,"vertical":0.2}],"highlights":[]}""",
        )
        val rawCoordinate = assertFailsWith<CloudDriveException> { harness.client.downloadRoute(detail) }
        assertEquals(CloudFailureKind.CONTRACT, rawCoordinate.kind)

        harness.api.responses += jsonResponse(
            200,
            """{"items":[${driveJson(status = "PUBLISHED", extra = ",\"coverUrl\":\"https://evil.example.test/cover\"")}],"nextCursor":null}""",
        )
        val foreignAsset = assertFailsWith<CloudDriveException> { harness.client.feed(limit = 1) }
        assertEquals("ASSET_HOST_NOT_ALLOWED", foreignAsset.code)
    }

    @Test
    fun `publish patch and delete use expected version and typed conflicts`() = runBlocking {
        val harness = Harness()
        harness.api.responses += jsonResponse(200, """{"drive":${driveJson(status = "PUBLISHED", version = 2)}}""")
        assertEquals(2, harness.client.publishDrive("drive-remote-0001", 1).version)
        assertEquals(1, JsonCodec.parse(harness.api.requests.last().body!!).objectValue().integer("expectedVersion"))

        harness.api.responses += jsonResponse(200, """{"drive":${driveJson(version = 2)}}""")
        harness.client.updateDrive("drive-remote-0001", DrivePatch(1, title = "새 제목"))
        assertEquals(HttpMethod.PATCH, harness.api.requests.last().method)
        assertEquals(1, JsonCodec.parse(harness.api.requests.last().body!!).objectValue().integer("expectedVersion"))

        harness.api.responses += errorResponse(409, "VERSION_CONFLICT", retryable = false)
        val conflict = assertFailsWith<CloudDriveException> {
            harness.client.updateDrive("drive-remote-0001", DrivePatch(1, summary = "충돌"))
        }
        assertEquals(CloudFailureKind.VERSION_CONFLICT, conflict.kind)

        harness.api.responses += HttpResponse(204)
        harness.client.deleteDrive("drive-remote-0001", "d".repeat(64))
        assertEquals(HttpMethod.DELETE, harness.api.requests.last().method)
        assertEquals("d".repeat(64), harness.api.requests.last().headers["Idempotency-Key"])
    }

    @Test
    fun `missing owner resource stays indistinguishable and idempotency conflict is typed`() = runBlocking {
        val harness = Harness()
        harness.api.responses += errorResponse(404, "NOT_FOUND", retryable = false)
        val missing = assertFailsWith<CloudDriveException> { harness.client.deleteDrive("drive-remote-0001", "e".repeat(64)) }
        assertEquals(CloudFailureKind.NOT_FOUND, missing.kind)
        assertEquals("NOT_FOUND", missing.code)

        harness.api.responses += errorResponse(409, "IDEMPOTENCY_CONFLICT", retryable = false)
        val replay = assertFailsWith<CloudDriveException> { harness.client.createDrive(harness.input) }
        assertEquals(CloudFailureKind.IDEMPOTENCY_CONFLICT, replay.kind)

        harness.api.responses += errorResponse(409, "ASSET_MISMATCH", retryable = false)
        val manifestMismatch = assertFailsWith<CloudDriveException> {
            harness.client.refreshUploads("drive-remote-0001", harness.manifests)
        }
        assertEquals(CloudFailureKind.VALIDATION, manifestMismatch.kind)

        harness.api.responses += errorResponse(409, "UPLOAD_INCOMPLETE", retryable = true)
        val incomplete = assertFailsWith<CloudDriveException> {
            harness.client.publishDrive("drive-remote-0001", 1)
        }
        assertEquals(CloudFailureKind.UPLOAD_INCOMPLETE, incomplete.kind)
    }

    @Test
    fun `server error identifiers are bounded before crossing the client boundary`() = runBlocking {
        val harness = Harness()
        harness.api.responses += jsonResponse(
            400,
            """{"error":{"code":"${"X".repeat(65)}","retryable":false,"requestId":"bad request id"}}""",
        )

        val failure = assertFailsWith<CloudDriveException> { harness.client.createDrive(harness.input) }

        assertEquals(CloudFailureKind.VALIDATION, failure.kind)
        assertEquals("HTTP_400", failure.code)
        assertEquals(null, failure.requestId)
    }

    @Test
    fun `server retry hint cannot turn terminal client errors into worker retries`() = runBlocking {
        val harness = Harness()
        harness.api.responses += errorResponse(400, "VALIDATION_FAILED", retryable = true)
        val validation = assertFailsWith<CloudDriveException> { harness.client.createDrive(harness.input) }
        assertEquals(CloudFailureKind.VALIDATION, validation.kind)
        assertFalse(validation.retryable)

        harness.api.responses += errorResponse(401, "TOKEN_REJECTED", retryable = true)
        val authentication = assertFailsWith<CloudDriveException> { harness.client.createDrive(harness.input) }
        assertEquals(CloudFailureKind.AUTHENTICATION, authentication.kind)
        assertFalse(authentication.retryable)

        harness.api.responses += errorResponse(503, "SERVER_BUSY", retryable = false)
        val unavailable = assertFailsWith<CloudDriveException> { harness.client.createDrive(harness.input) }
        assertEquals(CloudFailureKind.RETRYABLE, unavailable.kind)
        assertTrue(unavailable.retryable)
    }

    @Test
    fun `서버 401은 현재 Cognito 세션을 즉시 무효화한다`() = runBlocking {
        var invalidated = false
        val session = object : CognitoSessionSource {
            override suspend fun freshAccessToken() = "valid-looking-token"
            override fun onSessionInvalidated() { invalidated = true }
        }
        val api = FakeTransport().also {
            it.responses += errorResponse(401, "TOKEN_REJECTED", retryable = false)
        }
        val client = CloudDriveClient(
            config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
            auth = InMemoryCognitoAuthSessionProvider(session),
            apiTransport = api,
            objectTransport = FakeObjectTransport(),
        )

        val failure = assertFailsWith<CloudDriveException> { client.feed() }

        assertEquals(CloudFailureKind.AUTHENTICATION, failure.kind)
        assertTrue(invalidated)
    }

    @Test
    fun `과대하거나 손상된 401 본문도 Cognito 세션 무효화를 우회하지 못한다`() = runBlocking {
        var invalidated = false
        val session = object : CognitoSessionSource {
            override suspend fun freshAccessToken() = "valid-looking-token"
            override fun onSessionInvalidated() { invalidated = true }
        }
        val api = FakeTransport().also {
            it.responses += HttpResponse(401, body = ByteArray(2 * 1024 * 1024 + 1))
        }
        val client = CloudDriveClient(
            config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
            auth = InMemoryCognitoAuthSessionProvider(session),
            apiTransport = api,
            objectTransport = FakeObjectTransport(),
        )

        val failure = assertFailsWith<CloudDriveException> { client.feed() }

        assertEquals(CloudFailureKind.AUTHENTICATION, failure.kind)
        assertEquals("HTTP_401", failure.code)
        assertFalse(failure.retryable)
        assertTrue(invalidated)
    }

    @Test
    fun `동의 갱신 요구는 작업 대기열까지 전용 실패로 전달한다`() = runBlocking {
        val harness = Harness()
        harness.api.responses += errorResponse(422, "CONSENT_RENEWAL_REQUIRED", retryable = false)
        val clientFailure = assertFailsWith<CloudDriveException> { harness.client.createDrive(harness.input) }
        assertEquals(CloudFailureKind.CONSENT_RENEWAL_REQUIRED, clientFailure.kind)

        harness.api.responses += errorResponse(422, "CONSENT_RENEWAL_REQUIRED", retryable = false)
        val adapter = CloudRemoteDriveApi(harness.client, "2026-11-07.v1")
        val remoteFailure = assertFailsWith<RemoteDriveException> {
            adapter.createDrive(testDraft().clientRequestId, testDraft())
        }
        assertEquals(RemoteFailureCode.CONSENT_RENEWAL_REQUIRED, remoteFailure.code)
        assertFalse(remoteFailure.retryable)
    }

    @Test
    fun `delete auth conflict and server responses preserve bounded retry policy inputs`() = runBlocking {
        val harness = Harness()
        val key = "f".repeat(64)
        harness.api.responses += errorResponse(403, "FORBIDDEN", retryable = false)
        assertEquals(
            CloudFailureKind.FORBIDDEN,
            assertFailsWith<CloudDriveException> { harness.client.deleteDrive("drive-remote-0001", key) }.kind,
        )
        harness.api.responses += errorResponse(409, "VERSION_CONFLICT", retryable = false)
        assertEquals(
            CloudFailureKind.VERSION_CONFLICT,
            assertFailsWith<CloudDriveException> { harness.client.deleteDrive("drive-remote-0001", key) }.kind,
        )
        harness.api.responses += errorResponse(409, "RESOURCE_CONFLICT", retryable = true)
        val arbitraryConflict = assertFailsWith<CloudDriveException> {
            harness.client.deleteDrive("drive-remote-0001", key)
        }
        assertEquals(CloudFailureKind.VERSION_CONFLICT, arbitraryConflict.kind)
        assertEquals(false, arbitraryConflict.retryable)
        harness.api.responses += errorResponse(409, "IDEMPOTENCY_CONFLICT", retryable = true)
        val idempotencyConflict = assertFailsWith<CloudDriveException> {
            harness.client.deleteDrive("drive-remote-0001", key)
        }
        assertEquals(CloudFailureKind.IDEMPOTENCY_CONFLICT, idempotencyConflict.kind)
        assertEquals(false, idempotencyConflict.retryable)
        harness.api.responses += HttpResponse(409, body = "not-json".toByteArray())
        val malformedConflict = assertFailsWith<CloudDriveException> {
            harness.client.deleteDrive("drive-remote-0001", key)
        }
        assertEquals(CloudFailureKind.VERSION_CONFLICT, malformedConflict.kind)
        assertEquals(false, malformedConflict.retryable)
        harness.api.responses += errorResponse(503, "SERVER_BUSY", retryable = true)
        val busy = assertFailsWith<CloudDriveException> { harness.client.deleteDrive("drive-remote-0001", key) }
        assertEquals(CloudFailureKind.RETRYABLE, busy.kind)
        assertEquals(true, busy.retryable)
    }

    @Test
    fun `worker delete adapter accepts only 204 and preserves ambiguous 404`() = runBlocking {
        val harness = Harness()
        val adapter = CloudRemoteDeleteApi(harness.client)
        val key = "1".repeat(64)
        harness.api.responses += HttpResponse(204)
        assertEquals(RemoteDeleteConfirmed, adapter.deletePublishedDrive("drive-remote-0001", key))
        harness.api.responses += errorResponse(404, "NOT_FOUND", retryable = false)
        val missing = assertFailsWith<RemoteDeleteException> {
            adapter.deletePublishedDrive("drive-remote-0001", key)
        }
        assertEquals(DeleteFailureCode.NOT_FOUND, missing.failureCode)
        harness.api.responses += errorResponse(409, "IDEMPOTENCY_CONFLICT", retryable = true)
        val conflict = assertFailsWith<RemoteDeleteException> {
            adapter.deletePublishedDrive("drive-remote-0001", key)
        }
        assertEquals(DeleteFailureCode.IDEMPOTENCY_CONFLICT, conflict.failureCode)
    }

    @Test
    fun `transport diagnostics redact token url headers and bodies`() {
        val request = HttpRequest(
            HttpMethod.POST,
            URI("https://api.example.test/drives"),
            mapOf("Authorization" to "Bearer secret"),
            "private-body".toByteArray(),
        )
        val response = HttpResponse(422, mapOf("x-private" to "secret"), "private-response".toByteArray())
        assertFalse(request.toString().contains("secret"))
        assertFalse(request.toString().contains("api.example"))
        assertFalse(response.toString().contains("private"))
        assertEquals("BearerToken([REDACTED])", BearerToken.inMemory("secret").toString())
    }

    @Test
    fun `conflict review adapter returns only bounded snapshot and hides owner absence`() = runBlocking {
        val harness = Harness()
        harness.api.responses += HttpResponse(
            200,
            body = """{"drive":${driveJson(extra = ",\"coverUrl\":\"https://upload.example.test/private-cover\",\"routeUrl\":\"https://upload.example.test/private-route\",\"routeSchemaVersion\":1")}}""".toByteArray(),
        )
        val snapshot = CloudRemoteConflictReviewApi(harness.client).getOwnedDrive("drive-remote-0001")
        assertEquals("drive-remote-0001", snapshot.remoteDriveId)
        assertFalse(snapshot.toString().contains("upload.example"))

        harness.api.responses += errorResponse(404, "NOT_FOUND", retryable = false)
        val missing = assertFailsWith<RemoteDriveException> {
            CloudRemoteConflictReviewApi(harness.client).getOwnedDrive("drive-remote-0001")
        }
        assertEquals(RemoteFailureCode.NOT_FOUND, missing.code)
    }

    private class Harness {
        val coverBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        private val routeBytes = """{"schemaVersion":1,"path":[]}""".toByteArray()
        val manifests = listOf(
            manifest(AssetKind.COVER, "image/jpeg", coverBytes),
            manifest(AssetKind.ROUTE, "application/json", routeBytes),
        )
        val input = CreateDriveInput(
            clientRequestId = "drive-request-0001",
            title = "바닷길",
            summary = "공개용 요약",
            distanceMeters = 0,
            durationSeconds = 0,
            highlightCount = 0,
            consentPolicyVersion = "2026-11-01",
            assets = manifests,
        )
        var tokenCount = 0
        var clockNow: Instant = Instant.parse("2026-11-01T10:00:00Z")
        val api = FakeTransport()
        val objects = FakeObjectTransport()
        val client = CloudDriveClient(
            config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
            auth = AuthSessionProvider { BearerToken.inMemory("fresh-token-${++tokenCount}") },
            apiTransport = api,
            objectTransport = objects,
            clock = { clockNow },
        )
    }

    private class FakeTransport : ApiHttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val responses = ArrayDeque<HttpResponse>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class FakeObjectTransport : ObjectUploadTransport {
        val requests = mutableListOf<HttpRequest>()
        val responses = ArrayDeque<HttpResponse>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private fun createResponse(manifests: List<AssetManifest>): String =
        """{"drive":${driveJson()},"uploads":${uploadsJson(manifests)}}"""

    private fun uploadSessionResponse(
        manifests: List<AssetManifest>,
        host: String = "upload.example.test",
        checksum: String? = null,
        signedHeaders: String = "content-type%3Bhost%3Bx-amz-checksum-sha256",
    ): String = """{"drive":${driveJson()},"uploads":${uploadsJson(manifests, host, checksum, signedHeaders)}}"""

    private fun uploadsJson(
        manifests: List<AssetManifest>,
        host: String = "upload.example.test",
        checksum: String? = null,
        signedHeaders: String = "content-type%3Bhost%3Bx-amz-checksum-sha256",
    ): String = manifests.joinToString(prefix = "[", postfix = "]") { asset ->
        val signedChecksum = checksum ?: hexToBase64(asset.sha256Hex)
        """{"kind":"${asset.kind}","method":"PUT","url":"https://$host/${asset.kind.name.lowercase()}?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=TESTACCESSKEY00000001%2F20261101%2Fap-northeast-2%2Fs3%2Faws4_request&X-Amz-Date=20261101T100000Z&X-Amz-Expires=600&X-Amz-SignedHeaders=$signedHeaders&X-Amz-Signature=${"a".repeat(64)}","requiredHeaders":{"Content-Type":"${asset.contentType}","x-amz-checksum-sha256":"$signedChecksum"},"maxBytes":${asset.sizeBytes},"expiresAt":"2026-11-01T10:10:00Z"}"""
    }

    private fun validInstruction(manifest: AssetManifest) = UploadInstruction(
        manifest.kind,
        HttpMethod.PUT,
        URI("https://upload.example.test/asset?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=TESTACCESSKEY00000001%2F20261101%2Fap-northeast-2%2Fs3%2Faws4_request&X-Amz-Date=20261101T100000Z&X-Amz-Expires=600&X-Amz-SignedHeaders=content-type%3Bhost%3Bx-amz-checksum-sha256&X-Amz-Signature=${"a".repeat(64)}"),
        mapOf("Content-Type" to manifest.contentType, "x-amz-checksum-sha256" to hexToBase64(manifest.sha256Hex)),
        manifest.sizeBytes,
        "2026-11-01T10:10:00Z",
    )
}

private fun manifest(kind: AssetKind, type: String, bytes: ByteArray): AssetManifest = AssetManifest(
    kind,
    type,
    bytes.size.toLong(),
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
)

private fun AssetUploadCommand.testManifest() = AssetManifest(kind, contentType, content.size.toLong(), checksumSha256Hex)

private fun testDraft() = DraftSnapshot(
    id = "local-draft-0001",
    recordingId = "recording-0001",
    clientRequestId = "drive-request-0001",
    title = "바닷길",
    description = "공개용 요약",
    coverPreset = "route-memory-default",
    originalContentRef = "local-recording://recording-0001",
    state = "QUEUED",
    routePoints = listOf(
        NormalizedRoutePoint(0, .2f, .7f),
        NormalizedRoutePoint(1, .8f, .3f),
    ),
)

private fun driveJson(status: String = "DRAFT", version: Int = 1, extra: String = ""): String {
    val published = if (status == "PUBLISHED") ",\"publishedAt\":\"2026-11-01T10:05:00Z\"" else ""
    return """{"id":"drive-remote-0001","status":"$status","version":$version,"title":"바닷길","summary":"공개용 요약","distanceMeters":0,"durationSeconds":0,"highlightCount":0,"createdAt":"2026-11-01T10:00:00Z"$published$extra}"""
}

private fun jsonResponse(status: Int, json: String) = HttpResponse(status, body = json.toByteArray())

private fun errorResponse(status: Int, code: String, retryable: Boolean) = jsonResponse(
    status,
    """{"error":{"code":"$code","message":"safe","retryable":$retryable,"requestId":"request-safe-0001"}}""",
)

private fun hexToBase64(value: String): String = Base64.getEncoder().encodeToString(
    ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
)
