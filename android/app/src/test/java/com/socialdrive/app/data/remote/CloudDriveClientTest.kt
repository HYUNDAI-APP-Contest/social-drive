package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudDriveClientTest {
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
        )
        val adapter = CloudRemoteDriveApi(harness.client, "2026-11-01")
        val assets = draft.assetUploads()
        harness.api.responses += jsonResponse(201, createResponse(assets.map(AssetUploadCommand::testManifest)))
        assertEquals("drive-remote-0001", adapter.createDrive(draft.clientRequestId, draft))

        harness.api.responses += jsonResponse(200, uploadSessionResponse(assets.map(AssetUploadCommand::testManifest)))
        harness.objects.responses += HttpResponse(200)
        harness.objects.responses += HttpResponse(200)
        adapter.uploadAssets("drive-remote-0001", assets)
        assertEquals(HttpMethod.POST, harness.api.requests.last().method)
        assertTrue(harness.api.requests.last().uri.path.endsWith("/drives/drive-remote-0001/uploads"))
        assertEquals(2, harness.objects.requests.size)
        assertTrue(harness.objects.requests.all { request -> request.headers.keys.none { it.equals("authorization", true) } })

        harness.api.responses += jsonResponse(
            200,
            """{"drive":${driveJson(version = 7, extra = ",\"coverUrl\":\"https://read.example.test/c\",\"routeUrl\":\"https://read.example.test/r\",\"routeSchemaVersion\":1")}}""",
        )
        harness.api.responses += jsonResponse(200, """{"drive":${driveJson(status = "PUBLISHED", version = 8)}}""")
        adapter.publishDrive("drive-remote-0001", draft.clientRequestId)
        assertEquals(7, JsonCodec.parse(harness.api.requests.last().body!!).objectValue().integer("expectedVersion"))
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
    }

    @Test
    fun `feed forwards an opaque cursor without decoding and maps detail status`() = runBlocking {
        val harness = Harness()
        val opaque = "opaque_cursor_123456789012345678901234"
        harness.api.responses += jsonResponse(
            200,
            """{"items":[${driveJson(status = "PUBLISHED", extra = ",\"coverUrl\":\"https://read.example.test/cover?sig=secret\"")}],"nextCursor":"$opaque"}""",
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
            """{"drive":${driveJson(extra = ",\"coverUrl\":\"https://read.example.test/c\",\"routeUrl\":\"https://read.example.test/r\",\"routeSchemaVersion\":1")}}""",
        )
        val detail = harness.client.getDrive("drive-remote-0001")
        assertEquals(DriveStatus.DRAFT, detail.drive.status)
        assertEquals(1, detail.routeSchemaVersion)
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
        harness.client.deleteDrive("drive-remote-0001")
        assertEquals(HttpMethod.DELETE, harness.api.requests.last().method)
    }

    @Test
    fun `missing owner resource stays indistinguishable and idempotency conflict is typed`() = runBlocking {
        val harness = Harness()
        harness.api.responses += errorResponse(404, "NOT_FOUND", retryable = false)
        val missing = assertFailsWith<CloudDriveException> { harness.client.deleteDrive("drive-remote-0001") }
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
        assertEquals(CloudFailureKind.RETRYABLE, incomplete.kind)
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
        val api = FakeTransport()
        val objects = FakeObjectTransport()
        val client = CloudDriveClient(
            config = CloudDriveClientConfig(URI("https://api.example.test/dev"), setOf("upload.example.test")),
            auth = AuthSessionProvider { BearerToken.inMemory("fresh-token-${++tokenCount}") },
            apiTransport = api,
            objectTransport = objects,
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
        """{"kind":"${asset.kind}","method":"PUT","url":"https://$host/${asset.kind.name.lowercase()}?X-Amz-SignedHeaders=$signedHeaders&X-Amz-Signature=private","requiredHeaders":{"Content-Type":"${asset.contentType}","x-amz-checksum-sha256":"$signedChecksum"},"maxBytes":${asset.sizeBytes},"expiresAt":"2026-11-01T10:10:00.000Z"}"""
    }

    private fun validInstruction(manifest: AssetManifest) = UploadInstruction(
        manifest.kind,
        HttpMethod.PUT,
        URI("https://upload.example.test/asset?X-Amz-SignedHeaders=content-type%3Bhost%3Bx-amz-checksum-sha256&X-Amz-Signature=private"),
        mapOf("Content-Type" to manifest.contentType, "x-amz-checksum-sha256" to hexToBase64(manifest.sha256Hex)),
        manifest.sizeBytes,
        "2026-11-01T10:10:00.000Z",
    )
}

private fun manifest(kind: AssetKind, type: String, bytes: ByteArray): AssetManifest = AssetManifest(
    kind,
    type,
    bytes.size.toLong(),
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
)

private fun AssetUploadCommand.testManifest() = AssetManifest(kind, contentType, content.size.toLong(), checksumSha256Hex)

private fun driveJson(status: String = "DRAFT", version: Int = 1, extra: String = ""): String =
    """{"id":"drive-remote-0001","status":"$status","version":$version,"title":"바닷길","summary":"공개용 요약","distanceMeters":0,"durationSeconds":0,"highlightCount":0,"createdAt":"2026-11-01T10:00:00.000Z"$extra}"""

private fun jsonResponse(status: Int, json: String) = HttpResponse(status, body = json.toByteArray())

private fun errorResponse(status: Int, code: String, retryable: Boolean) = jsonResponse(
    status,
    """{"error":{"code":"$code","message":"safe","retryable":$retryable,"requestId":"request-safe-0001"}}""",
)

private fun hexToBase64(value: String): String = Base64.getEncoder().encodeToString(
    ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
)
