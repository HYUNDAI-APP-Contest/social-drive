package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import com.socialdrive.app.domain.NormalizedRoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBackendContractHarnessTest {
    @Test
    fun `real client and handler complete privacy-safe lifecycle with deterministic conflicts and delete replay`() = contractTest {
        LocalBackendContractBridge().use { bridge ->
            val client = client(bridge)
            val remote = CloudRemoteDriveApi(client, POLICY_VERSION)
            val draft = privateDraft()
            val commands = draft.assetUploads()

            val created = remote.createDrive(draft.clientRequestId, draft)
            assertEquals("contract-drive-0001", created.id)
            assertEquals(created, remote.createDrive(draft.clientRequestId, draft))
            assertEquals(1, bridge.snapshot().driveCount)
            assertEquals(listOf(201, 200), bridge.snapshot().createStatuses)

            val idempotency = assertFailsWith<CloudDriveException> {
                client.createDrive(createInput(draft.copy(title = "다른 제목")))
            }
            assertEquals(CloudFailureKind.IDEMPOTENCY_CONFLICT, idempotency.kind)
            assertEquals("IDEMPOTENCY_CONFLICT", idempotency.code)
            assertFalse(idempotency.retryable)

            val stranger = client(bridge, "contract-user-b")
            val hidden = assertFailsWith<CloudDriveException> { stranger.getDrive(created.id) }
            assertEquals(CloudFailureKind.NOT_FOUND, hidden.kind)
            val hiddenPatch = assertFailsWith<CloudDriveException> {
                stranger.updateDrive(created.id, DrivePatch(created.version, title = "숨겨진 수정"))
            }
            assertEquals(CloudFailureKind.NOT_FOUND, hiddenPatch.kind)
            val hiddenDelete = assertFailsWith<CloudDriveException> {
                stranger.deleteDrive(created.id, "e".repeat(64))
            }
            assertEquals(CloudFailureKind.NOT_FOUND, hiddenDelete.kind)

            val incomplete = assertFailsWith<CloudDriveException> { client.publishDrive(created.id, created.version) }
            assertEquals(CloudFailureKind.UPLOAD_INCOMPLETE, incomplete.kind)
            assertTrue(incomplete.retryable)

            val objectRequestsBeforeMismatch = bridge.capturedRequests.count { it.target == "object" }
            val checksumMismatch = assertFailsWith<CloudDriveException> {
                client.refreshUploads(created.id, commands.map {
                    it.testManifest().let { manifest ->
                        if (manifest.kind == AssetKind.ROUTE) manifest.copy(sha256Hex = "0".repeat(64)) else manifest
                    }
                })
            }
            assertEquals(CloudFailureKind.VALIDATION, checksumMismatch.kind)
            assertEquals(objectRequestsBeforeMismatch, bridge.capturedRequests.count { it.target == "object" })

            remote.uploadAssets(created.id, commands)
            val published = remote.publishDrive(created.id, created.version, draft.clientRequestId)
            assertEquals(2, published.version)
            assertEquals(created.id, client.feed().items.single().drive.id)
            assertEquals(1, client.getDrive(created.id).routeSchemaVersion)

            val updated = client.updateDrive(created.id, DrivePatch(published.version, title = "수정된 바닷길"))
            assertEquals(3, updated.version)
            val conflict = assertFailsWith<CloudDriveException> {
                client.updateDrive(created.id, DrivePatch(published.version, summary = "오래된 수정"))
            }
            assertEquals(CloudFailureKind.VERSION_CONFLICT, conflict.kind)
            assertFalse(conflict.retryable)

            assertPrivacyBoundary(bridge, draft)
            val beforeDelete = bridge.snapshot()
            assertEquals(2, beforeDelete.objectCount)
            assertTrue(beforeDelete.forbiddenKeys.isEmpty(), "Forbidden boundary key: ${beforeDelete.forbiddenKeys.firstOrNull()}")
            assertEquals(0, beforeDelete.sentinelMatches)
            assertEquals(listOf("highlights", "path", "schemaVersion"), beforeDelete.routeKeys.single())
            assertTrue(beforeDelete.pointKeys.all { it.all { key -> key in setOf("horizontal", "vertical", "progress") } })
            assertEquals(0, beforeDelete.objectAuthorizationCount)
            assertTrue(beforeDelete.logKeys.flatten().all { it in setOf("requestId", "routeKey", "status", "errorCode") })

            val deleteKey = "d".repeat(64)
            client.deleteDrive(created.id, deleteKey)
            client.deleteDrive(created.id, deleteKey)
            val conflictingDelete = assertFailsWith<CloudDriveException> {
                client.deleteDrive(created.id, "f".repeat(64))
            }
            assertEquals(CloudFailureKind.IDEMPOTENCY_CONFLICT, conflictingDelete.kind)
            assertEquals("IDEMPOTENCY_CONFLICT", conflictingDelete.code)
            assertFalse(conflictingDelete.retryable)
            assertTrue(client.feed().items.isEmpty())
            assertEquals(CloudFailureKind.NOT_FOUND, assertFailsWith<CloudDriveException> { client.getDrive(created.id) }.kind)
            assertPrivacyBoundary(bridge, draft)
            val afterDelete = bridge.snapshot()
            assertEquals(0, afterDelete.objectCount)
            assertTrue(afterDelete.forbiddenKeys.isEmpty(), "Forbidden boundary key: ${afterDelete.forbiddenKeys.firstOrNull()}")
            assertEquals(0, afterDelete.sentinelMatches)
            assertEquals(0, afterDelete.objectAuthorizationCount)
            assertEquals(404, afterDelete.deleteStatuses.first())
            assertEquals(listOf(204, 204, 409), afterDelete.deleteStatuses.takeLast(3))
            assertEquals(listOf(deleteKey, deleteKey, "f".repeat(64)), afterDelete.deleteIdempotencyKeys.takeLast(3))
            assertTrue(afterDelete.deleteIdempotencyKeys.all { it.matches(Regex("[a-f0-9]{64}")) })
            assertTrue(afterDelete.logKeys.flatten().all { it in setOf("requestId", "routeKey", "status", "errorCode") })
        }
    }

    @Test
    fun `object adapter rejects MIME size and checksum mismatches without storing bytes`() = contractTest {
        LocalBackendContractBridge().use { bridge ->
            val draft = privateDraft()
            val commands = draft.assetUploads()
            val created = client(bridge).createDrive(createInput(draft))
            val cover = commands.single { it.kind == AssetKind.COVER }
            val instruction = created.uploads.single { it.kind == AssetKind.COVER }

            val wrongMime = instruction.requiredHeaders.toMutableMap().apply { this["Content-Type"] = "image/webp" }
            assertEquals(422, bridge.execute(HttpRequest(HttpMethod.PUT, instruction.uri, wrongMime, cover.content)).status)

            val wrongSize = cover.content + 0x00
            assertEquals(422, bridge.execute(HttpRequest(HttpMethod.PUT, instruction.uri, instruction.requiredHeaders, wrongSize)).status)

            val wrongChecksum = cover.content.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte() }
            assertEquals(422, bridge.execute(HttpRequest(HttpMethod.PUT, instruction.uri, instruction.requiredHeaders, wrongChecksum)).status)

            val snapshot = bridge.snapshot()
            assertEquals(0, snapshot.objectCount)
            assertEquals(0, snapshot.objectAuthorizationCount)
        }
    }

    @Test
    fun `loading authentication retryable transport and malformed response map to stable states`() = contractTest {
        LocalBackendContractBridge().use { bridge ->
            val client = client(bridge)
            val gate = CompletableDeferred<Unit>()
            bridge.responseGate = gate
            val before = bridge.capturedRequests.size
            val handledBefore = bridge.snapshot().requestCount
            val loading = async { client.feed() }
            yield()
            assertFalse(loading.isCompleted)
            assertEquals(before + 1, bridge.capturedRequests.size)
            assertEquals("api", bridge.capturedRequests.last().target)
            assertEquals(handledBefore + 1, bridge.snapshot().requestCount)
            gate.complete(Unit)
            bridge.responseGate = null
            assertTrue(loading.await().items.isEmpty())

            val authFailure = assertFailsWith<CloudDriveException> { client(bridge, "wrong-token").feed() }
            assertEquals(CloudFailureKind.AUTHENTICATION, authFailure.kind)
            assertFalse(authFailure.retryable)
            assertEquals(0, bridge.snapshot().driveCount)

            bridge.failNextApiRequest = true
            val retryable = assertFailsWith<CloudDriveException> { client.createDrive(createInput(privateDraft())) }
            assertEquals(CloudFailureKind.RETRYABLE, retryable.kind)
            assertEquals("API_TRANSPORT", retryable.code)
            assertTrue(retryable.retryable)
            val retried = client.createDrive(createInput(privateDraft()))
            assertEquals("contract-drive-0001", retried.drive.id)
            assertEquals(1, bridge.snapshot().driveCount)

            bridge.malformNextApiResponse = true
            val malformed = assertFailsWith<CloudDriveException> { client.feed() }
            assertEquals(CloudFailureKind.CONTRACT, malformed.kind)
            assertEquals("INVALID_RESPONSE", malformed.code)
            assertFalse(malformed.retryable)
            assertFalse(malformed.toString().contains("private-response-marker"))
        }
    }

    @Test
    fun `bounded complete manifest is rejected locally before object transport when altered`() = contractTest {
        LocalBackendContractBridge().use { bridge ->
            val client = client(bridge)
            val manifests = privateDraft().assetUploads().map { it.testManifest() }
            val before = bridge.capturedRequests.size
            assertFailsWith<IllegalArgumentException> { client.refreshUploads("contract-drive-0001", manifests.take(1)) }
            assertFailsWith<IllegalArgumentException> { client.refreshUploads("contract-drive-0001", listOf(manifests[0], manifests[0])) }
            assertFailsWith<IllegalArgumentException> {
                client.refreshUploads("contract-drive-0001", manifests.map {
                    if (it.kind == AssetKind.ROUTE) it.copy(sizeBytes = 1024L * 1024 + 1) else it
                })
            }
            assertEquals(before, bridge.capturedRequests.size)

            val created = client.createDrive(createInput(privateDraft())).drive
            val objectRequestsBefore = bridge.capturedRequests.count { it.target == "object" }
            val partialResponse = bridge.execute(
                rawUploadRefreshRequest(
                    driveId = created.id,
                    manifests = manifests.take(1),
                ),
            )
            assertEquals(409, partialResponse.status)
            val error = JsonCodec.parse(partialResponse.body).objectValue().getValue("error").objectValue()
            assertEquals("ASSET_MISMATCH", error.string("code"))
            assertEquals(objectRequestsBefore, bridge.capturedRequests.count { it.target == "object" })
            assertEquals(0, bridge.snapshot().objectCount)
        }
    }

    @Test
    fun `malformed and oversized request bodies return safe handler envelopes without echo`() = contractTest {
        LocalBackendContractBridge().use { bridge ->
            val marker = "private-request-marker-must-not-echo"
            val malformed = bridge.execute(rawApiRequest("{\"title\":\"$marker\"".toByteArray()))
            assertSafeError(malformed, expectedStatus = 400, expectedCode = "INVALID_JSON", marker = marker)

            val oversizedBody = "{\"padding\":\"${"x".repeat(64 * 1024)},\"marker\":\"$marker\"}".toByteArray()
            assertTrue(oversizedBody.size > 64 * 1024)
            val oversized = bridge.execute(rawApiRequest(oversizedBody))
            assertSafeError(oversized, expectedStatus = 413, expectedCode = "REQUEST_TOO_LARGE", marker = marker)
        }
    }

    private fun rawApiRequest(body: ByteArray) = HttpRequest(
        method = HttpMethod.POST,
        uri = URI("https://api.contract.invalid/contract/drives"),
        headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer contract-user-a",
            "Content-Type" to "application/json",
            "Idempotency-Key" to "contract-request-raw-0001",
        ),
        body = body,
    )

    private fun rawUploadRefreshRequest(driveId: String, manifests: List<AssetManifest>) = HttpRequest(
        method = HttpMethod.POST,
        uri = URI("https://api.contract.invalid/contract/drives/$driveId/uploads"),
        headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer contract-user-a",
            "Content-Type" to "application/json",
        ),
        body = JsonCodec.encode(
            jsonObject(
                "assets" to JsonValue.Array(manifests.map { manifest ->
                    jsonObject(
                        "kind" to jsonString(manifest.kind.name),
                        "contentType" to jsonString(manifest.contentType),
                        "sizeBytes" to jsonNumber(manifest.sizeBytes),
                        "sha256" to jsonString(manifest.sha256Hex),
                    )
                }),
            ),
        ),
    )

    private fun assertSafeError(response: HttpResponse, expectedStatus: Int, expectedCode: String, marker: String) {
        assertEquals(expectedStatus, response.status)
        val root = JsonCodec.parse(response.body).objectValue()
        val error = root.getValue("error").objectValue()
        assertEquals(expectedCode, error.string("code"))
        assertFalse(error.boolean("retryable"))
        assertFalse(response.body.toString(Charsets.UTF_8).contains(marker))
    }

    private fun contractTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) { block() }
    }

    private fun client(bridge: LocalBackendContractBridge, token: String = "contract-user-a") = CloudDriveClient(
        config = CloudDriveClientConfig(
            URI("https://api.contract.invalid/contract"),
            setOf("upload.contract.invalid", "read.contract.invalid"),
        ),
        auth = AuthSessionProvider { BearerToken.inMemory(token) },
        apiTransport = bridge,
        objectTransport = bridge,
        clock = { Instant.parse("2026-11-01T10:00:00Z") },
    )

    private fun createInput(draft: DraftSnapshot): CreateDriveInput = CreateDriveInput(
        clientRequestId = draft.clientRequestId,
        title = draft.title,
        summary = draft.description,
        distanceMeters = 0,
        durationSeconds = 0,
        highlightCount = 0,
        consentPolicyVersion = POLICY_VERSION,
        assets = draft.assetUploads().map { it.testManifest() },
    )

    private fun assertPrivacyBoundary(bridge: LocalBackendContractBridge, draft: DraftSnapshot) {
        val forbiddenKeys = setOf("lat", "lng", "latitude", "longitude", "vin", "speed", "velocity", "acceleration", "brake", "steering", "gear", "rpm", "vehicleid", "deviceid", "rawvehicle", "originalcontentref")
        val sentinels = listOf("37.566535", "126.977969", "KMHXX00XXXX000001", "173.25", "raw-vehicle-secret", draft.originalContentRef)
        val objectRequests = bridge.capturedRequests.filter { it.target == "object" }
        assertEquals(2, objectRequests.size)
        assertTrue(objectRequests.all { it.method == HttpMethod.PUT }, "Every object upload must use PUT")
        bridge.capturedRequests.forEach { request ->
            val boundary = request.uri + request.headers.entries.joinToString { it.key + it.value } + request.body.toString(Charsets.UTF_8)
            sentinels.forEach { sentinel -> assertFalse(boundary.contains(sentinel), "Private sentinel crossed ${request.target} boundary") }
            if (request.target == "api" && request.body.isNotEmpty()) {
                val json = JsonCodec.parse(request.body)
                val found = mutableSetOf<String>()
                collectForbiddenKeys(json, forbiddenKeys, found)
                assertTrue(found.isEmpty(), "Forbidden boundary key: ${found.firstOrNull()}")
            }
            if (request.target == "object" && request.headers["Content-Type"] == "application/json") {
                val route = JsonCodec.parse(request.body).objectValue()
                assertEquals(setOf("schemaVersion", "path", "highlights"), route.keys)
                route.array("path").forEach { point ->
                    val values = point.objectValue()
                    assertTrue(values.keys.all { it in setOf("horizontal", "vertical", "progress") })
                }
            }
            assertFalse(request.toString().contains("Bearer"))
            assertFalse(request.toString().contains("contract.invalid"))
        }
    }

    private fun collectForbiddenKeys(value: JsonValue, forbidden: Set<String>, found: MutableSet<String>) {
        when (value) {
            is JsonValue.Object -> value.values.forEach { (key, child) ->
                if (key.lowercase() in forbidden) found += key.lowercase()
                collectForbiddenKeys(child, forbidden, found)
            }
            is JsonValue.Array -> value.values.forEach { collectForbiddenKeys(it, forbidden, found) }
            else -> Unit
        }
    }

    private fun privateDraft() = DraftSnapshot(
        id = "local-draft-0001",
        recordingId = "deviceId=device-private-001;vehicleId=vehicle-private-001",
        clientRequestId = "contract-request-0001",
        title = "바닷길",
        description = "공개용 요약",
        coverPreset = "rawVehicle={vin:KMHXX00XXXX000001,speed:173.25,rpm:6200,gear:D}",
        originalContentRef = "local://37.566535,126.977969/raw-vehicle-secret",
        state = "QUEUED",
        routePoints = listOf(
            NormalizedRoutePoint(0, .2f, .7f),
            NormalizedRoutePoint(1, .8f, .3f),
        ),
    )

    companion object {
        private const val POLICY_VERSION = "2026-11-01"
        private const val TEST_TIMEOUT_MILLIS = 30_000L
    }
}

private fun AssetUploadCommand.testManifest() = AssetManifest(kind, contentType, content.size.toLong(), checksumSha256Hex)
