package com.socialdrive.app.data.remote

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.coroutines.CancellationException

/**
 * SDK-independent production API contract. It owns no database or logger and retains no session,
 * response body, presigned URL, or coordinate data between calls.
 */
class CloudDriveClient(
    private val config: CloudDriveClientConfig,
    private val auth: AuthSessionProvider,
    private val apiTransport: ApiHttpTransport,
    private val objectTransport: ObjectUploadTransport,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun createDrive(input: CreateDriveInput): CreateDriveResult {
        validateCreate(input)
        val response = api(
            method = HttpMethod.POST,
            path = "/drives",
            headers = mapOf("Idempotency-Key" to input.clientRequestId),
            body = createBody(input),
            expectedStatuses = setOf(200, 201),
        )
        val root = response.objectValue()
        val drive = parseDrive(root.requiredObject("drive"))
        return CreateDriveResult(drive, parseUploads(root, input.assets))
    }

    suspend fun refreshUploads(
        driveId: String,
        assets: List<AssetManifest>,
    ): UploadSession {
        requireSafeId(driveId)
        // Refresh is canonical: the backend compares the complete frozen COVER + ROUTE manifest.
        requireValidManifest(assets, requireBothKinds = true)
        val response = api(
            method = HttpMethod.POST,
            path = "/drives/$driveId/uploads",
            body = jsonObject("assets" to manifestsJson(assets)),
            expectedStatuses = setOf(200),
        ).objectValue()
        val drive = parseDrive(response.requiredObject("drive"))
        if (drive.status != DriveStatus.DRAFT) throw CloudContractException("INVALID_UPLOAD_STATUS")
        return UploadSession(drive, parseUploads(response, assets))
    }

    suspend fun uploadAsset(instruction: UploadInstruction, manifest: AssetManifest, content: ByteArray) {
        validateInstruction(instruction, manifest, content)
        val request = HttpRequest(
            method = HttpMethod.PUT,
            uri = instruction.uri,
            headers = instruction.requiredHeaders,
            body = content,
        )
        check(request.headers.keys.none { it.equals("authorization", ignoreCase = true) })
        val response = try {
            objectTransport.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw CloudDriveException(CloudFailureKind.RETRYABLE, "UPLOAD_TRANSPORT", retryable = true)
        }
        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "RESPONSE_TOO_LARGE", false)
        }
        if (response.status == 403) {
            throw CloudDriveException(CloudFailureKind.UPLOAD_EXPIRED, "UPLOAD_SESSION_EXPIRED", retryable = true)
        }
        if (response.status !in 200..299) {
            throw CloudDriveException(
                kind = if (response.status == 408 || response.status == 429 || response.status >= 500) CloudFailureKind.RETRYABLE else CloudFailureKind.UNKNOWN,
                code = "UPLOAD_REJECTED",
                retryable = response.status == 408 || response.status == 429 || response.status >= 500,
            )
        }
    }

    suspend fun publishDrive(driveId: String, expectedVersion: Int): DriveResource {
        requireSafeId(driveId)
        require(expectedVersion >= 1)
        val root = api(
            method = HttpMethod.POST,
            path = "/drives/$driveId/publish",
            body = jsonObject("expectedVersion" to jsonNumber(expectedVersion)),
            expectedStatuses = setOf(200),
        ).objectValue()
        val drive = parseDrive(root.requiredObject("drive"))
        if (drive.status != DriveStatus.PUBLISHED || drive.version < expectedVersion) {
            throw CloudContractException("INVALID_PUBLISH_STATUS")
        }
        return drive
    }

    suspend fun feed(limit: Int = 20, cursor: OpaqueFeedCursor? = null): FeedPage {
        require(limit in 1..50)
        val query = buildString {
            append("?limit=").append(limit)
            cursor?.let { append("&cursor=").append(percentEncode(it.rawValue())) }
        }
        val root = api(HttpMethod.GET, "/feed$query", expectedStatuses = setOf(200)).objectValue()
        val itemValues = root.array("items")
        if (itemValues.size > limit) throw CloudContractException("FEED_LIMIT_EXCEEDED")
        val items = itemValues.map { itemValue ->
            val item = itemValue.objectValue()
            val drive = parseDrive(item)
            if (drive.status != DriveStatus.PUBLISHED) throw CloudContractException("INVALID_FEED_STATUS")
            FeedItem(
                drive = drive,
                links = EphemeralAssetLinks(secureAssetUri(item.string("coverUrl")), routeUri = null),
            )
        }
        val cursorValue = root.optionalString("nextCursor")
        return FeedPage(items, cursorValue?.let(OpaqueFeedCursor::fromServer))
    }

    suspend fun getDrive(driveId: String): DriveDetail {
        requireSafeId(driveId)
        val drive = api(HttpMethod.GET, "/drives/$driveId", expectedStatuses = setOf(200))
            .objectValue().requiredObject("drive")
        return DriveDetail(
            drive = parseDrive(drive),
            links = EphemeralAssetLinks(
                coverUri = secureAssetUri(drive.string("coverUrl")),
                routeUri = secureAssetUri(drive.string("routeUrl")),
            ),
            routeSchemaVersion = drive.integer("routeSchemaVersion").also {
                if (it != 1) throw CloudContractException("INVALID_ROUTE_SCHEMA")
            },
        )
    }

    /** Downloads a short-lived public route without forwarding Cognito authorization. */
    suspend fun downloadRoute(detail: DriveDetail): PublishedRoute {
        if (detail.routeSchemaVersion != 1) throw CloudContractException("INVALID_ROUTE_SCHEMA")
        val uri = detail.links.routeUri ?: throw CloudContractException("MISSING_ROUTE_URL")
        val response = try {
            objectTransport.execute(HttpRequest(HttpMethod.GET, uri, emptyMap(), null))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw CloudDriveException(CloudFailureKind.RETRYABLE, "ROUTE_TRANSPORT", retryable = true)
        }
        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "RESPONSE_TOO_LARGE", false)
        }
        if (response.status == 403) {
            throw CloudDriveException(CloudFailureKind.RETRYABLE, "READ_LINK_EXPIRED", retryable = true)
        }
        if (response.status != 200) {
            throw CloudDriveException(
                if (response.status == 408 || response.status == 429 || response.status >= 500) CloudFailureKind.RETRYABLE else CloudFailureKind.UNKNOWN,
                "ROUTE_READ_REJECTED",
                retryable = response.status == 408 || response.status == 429 || response.status >= 500,
            )
        }
        return parsePublishedRoute(response.body)
    }

    /** Downloads a short-lived JPEG cover without forwarding Cognito authorization. */
    suspend fun downloadCover(detail: DriveDetail): ByteArray {
        val response = try {
            objectTransport.execute(HttpRequest(HttpMethod.GET, detail.links.coverUri, emptyMap(), null))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw CloudDriveException(CloudFailureKind.RETRYABLE, "COVER_TRANSPORT", retryable = true)
        }
        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "RESPONSE_TOO_LARGE", false)
        }
        if (response.status == 403) {
            throw CloudDriveException(CloudFailureKind.RETRYABLE, "READ_LINK_EXPIRED", retryable = true)
        }
        if (response.status != 200) {
            throw CloudDriveException(
                if (response.status == 408 || response.status == 429 || response.status >= 500) CloudFailureKind.RETRYABLE else CloudFailureKind.UNKNOWN,
                "COVER_READ_REJECTED",
                retryable = response.status == 408 || response.status == 429 || response.status >= 500,
            )
        }
        val bytes = response.body
        if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
            bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()
        ) throw CloudContractException("INVALID_COVER_IMAGE")
        return bytes.copyOf()
    }

    suspend fun updateDrive(driveId: String, patch: DrivePatch): DriveResource {
        requireSafeId(driveId)
        require(patch.expectedVersion >= 1)
        require(patch.title != null || patch.summary != null)
        patch.title?.let { require(it.isNotBlank() && it.codePointLength() <= 40) }
        patch.summary?.let { require(it.codePointLength() <= 160) }
        val values = linkedMapOf<String, JsonValue>("expectedVersion" to jsonNumber(patch.expectedVersion))
        patch.title?.let { values["title"] = jsonString(it) }
        patch.summary?.let { values["summary"] = jsonString(it) }
        val root = api(
            HttpMethod.PATCH,
            "/drives/$driveId",
            body = JsonValue.Object(values),
            expectedStatuses = setOf(200),
        ).objectValue()
        return parseDrive(root.requiredObject("drive"))
    }

    suspend fun deleteDrive(driveId: String, stableKeyHash: String) {
        requireSafeId(driveId)
        require(stableKeyHash.matches(Regex("[a-f0-9]{64}")))
        api(
            HttpMethod.DELETE,
            "/drives/$driveId",
            headers = mapOf("Idempotency-Key" to stableKeyHash),
            expectedStatuses = setOf(204),
            terminalDeleteConflict = true,
        )
    }

    private suspend fun api(
        method: HttpMethod,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: JsonValue? = null,
        expectedStatuses: Set<Int>,
        terminalDeleteConflict: Boolean = false,
    ): JsonValue {
        val token = try {
            auth.freshBearerToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthSessionTemporarilyUnavailableException) {
            throw CloudDriveException(
                CloudFailureKind.RETRYABLE,
                "AUTH_SESSION_TEMPORARILY_UNAVAILABLE",
                retryable = true,
            )
        } catch (_: Throwable) {
            throw CloudDriveException(CloudFailureKind.AUTHENTICATION, "AUTH_SESSION_UNAVAILABLE", retryable = false)
        }
        val encoded = body?.let(JsonCodec::encode)
        val requestHeaders = linkedMapOf("Accept" to "application/json", "Authorization" to token.authorizationHeader())
        if (encoded != null) requestHeaders["Content-Type"] = "application/json"
        requestHeaders.putAll(headers)
        val request = HttpRequest(method, apiUri(path), requestHeaders, encoded)
        val response = try {
            apiTransport.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw CloudDriveException(CloudFailureKind.RETRYABLE, "API_TRANSPORT", retryable = true)
        }
        if (response.status == 401) {
            runCatching { auth.onAuthenticationRejected() }
            if (response.body.size > MAX_RESPONSE_BYTES) {
                throw CloudDriveException(CloudFailureKind.AUTHENTICATION, "HTTP_401", retryable = false)
            }
        }
        if (response.body.size > MAX_RESPONSE_BYTES) throw CloudDriveException(CloudFailureKind.CONTRACT, "RESPONSE_TOO_LARGE", false)
        if (response.status !in expectedStatuses) throw parseApiError(response, terminalDeleteConflict)
        if (response.status == 204) return JsonValue.NullValue
        return try {
            JsonCodec.parse(response.body)
        } catch (known: CloudDriveException) {
            throw known
        } catch (_: Throwable) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "INVALID_RESPONSE", false)
        }
    }

    private fun parseApiError(response: HttpResponse, terminalDeleteConflict: Boolean): CloudDriveException {
        val transportRetry = response.status == 408 || response.status == 429 || response.status >= 500
        val envelope = runCatching { JsonCodec.parse(response.body).objectValue().requiredObject("error") }.getOrNull()
        val code = envelope?.get("code")
            ?.let { (it as? JsonValue.StringValue)?.value }
            ?.takeIf(ERROR_CODE_PATTERN::matches)
            ?: "HTTP_${response.status}"
        val declaredRetry = envelope?.get("retryable")?.let { (it as? JsonValue.BooleanValue)?.value } == true
        val retryable = transportRetry || (
            response.status == 409 &&
                code in setOf("UPLOAD_INCOMPLETE", "RESOURCE_CONFLICT") &&
                declaredRetry
            )
        val requestId = envelope?.get("requestId")
            ?.let { (it as? JsonValue.StringValue)?.value }
            ?.takeIf(REQUEST_ID_PATTERN::matches)
        if (terminalDeleteConflict && response.status == 409) {
            val kind = if (code == "IDEMPOTENCY_CONFLICT") {
                CloudFailureKind.IDEMPOTENCY_CONFLICT
            } else {
                CloudFailureKind.VERSION_CONFLICT
            }
            return CloudDriveException(kind, code, retryable = false, requestId = requestId)
        }
        val kind = when {
            code == "CONSENT_RENEWAL_REQUIRED" -> CloudFailureKind.CONSENT_RENEWAL_REQUIRED
            response.status == 401 -> CloudFailureKind.AUTHENTICATION
            response.status == 403 -> CloudFailureKind.FORBIDDEN
            response.status == 404 -> CloudFailureKind.NOT_FOUND
            code == "IDEMPOTENCY_CONFLICT" -> CloudFailureKind.IDEMPOTENCY_CONFLICT
            response.status == 409 && code in setOf("VERSION_CONFLICT", "CONFLICT") -> CloudFailureKind.VERSION_CONFLICT
            response.status == 409 && code == "UPLOAD_INCOMPLETE" -> CloudFailureKind.UPLOAD_INCOMPLETE
            response.status == 409 && code == "RESOURCE_CONFLICT" && retryable -> CloudFailureKind.RETRYABLE
            response.status == 409 && code == "ASSET_MISMATCH" -> CloudFailureKind.VALIDATION
            response.status in setOf(400, 413, 415, 422) -> CloudFailureKind.VALIDATION
            retryable -> CloudFailureKind.RETRYABLE
            else -> CloudFailureKind.UNKNOWN
        }
        return CloudDriveException(kind, code, retryable, requestId)
    }

    private fun parseUploads(root: Map<String, JsonValue>, manifests: List<AssetManifest>): List<UploadInstruction> {
        val byKind = manifests.associateBy(AssetManifest::kind)
        val parsed = root.array("uploads").map { value ->
            val upload = value.objectValue()
            val kind = enumValueOfOrContract<AssetKind>(upload.string("kind"))
            val manifest = byKind[kind] ?: throw CloudContractException("UNEXPECTED_UPLOAD")
            val method = enumValueOfOrContract<HttpMethod>(upload.string("method"))
            val requiredHeaders = upload.requiredObject("requiredHeaders").mapValues { (_, headerValue) ->
                (headerValue as? JsonValue.StringValue)?.value ?: throw CloudContractException("INVALID_UPLOAD_HEADERS")
            }
            val instruction = UploadInstruction(
                kind = kind,
                method = method,
                uri = secureUploadUri(upload.string("url")),
                requiredHeaders = requiredHeaders,
                maxBytes = upload.long("maxBytes"),
                expiresAt = upload.string("expiresAt"),
            )
            validateInstructionMetadata(instruction, manifest)
            instruction
        }
        if (parsed.size != byKind.size || parsed.map { it.kind }.toSet() != byKind.keys) {
            throw CloudContractException("INVALID_UPLOAD_SET")
        }
        return parsed
    }

    private fun validateInstruction(instruction: UploadInstruction, manifest: AssetManifest, content: ByteArray) {
        validateInstructionMetadata(instruction, manifest)
        if (content.size.toLong() != manifest.sizeBytes || content.size.toLong() > instruction.maxBytes) {
            throw CloudDriveException(CloudFailureKind.CONTRACT, "UPLOAD_SIZE_MISMATCH", false)
        }
        val actualHex = sha256Hex(content)
        if (actualHex != manifest.sha256Hex) throw CloudDriveException(CloudFailureKind.CONTRACT, "UPLOAD_CHECKSUM_MISMATCH", false)
    }

    private fun validateInstructionMetadata(instruction: UploadInstruction, manifest: AssetManifest) {
        if (instruction.kind != manifest.kind || instruction.method != HttpMethod.PUT || instruction.maxBytes != manifest.sizeBytes) {
            throw CloudContractException("INVALID_UPLOAD_INSTRUCTION")
        }
        if (instruction.requiredHeaders.keys.any { it.equals("authorization", ignoreCase = true) }) {
            throw CloudContractException("UPLOAD_AUTH_FORBIDDEN")
        }
        val normalizedRequired = instruction.requiredHeaders.keys.map(String::lowercase)
        if (normalizedRequired.size != normalizedRequired.toSet().size || normalizedRequired.toSet() != REQUIRED_UPLOAD_HEADERS) {
            throw CloudContractException("INVALID_UPLOAD_HEADERS")
        }
        val signedHeaders = instruction.uri.queryParameter("X-Amz-SignedHeaders")
            ?.split(';')?.map(String::lowercase).orEmpty()
        if (signedHeaders != SIGNED_UPLOAD_HEADER_ORDER) {
            throw CloudContractException("UNSIGNED_UPLOAD_HEADERS")
        }
        validateSigV4(instruction)
        val contentType = instruction.requiredHeaders.caseInsensitive("content-type")
        val checksum = instruction.requiredHeaders.caseInsensitive("x-amz-checksum-sha256")
        if (contentType != manifest.contentType || checksum != hexToBase64(manifest.sha256Hex)) {
            throw CloudContractException("UNSIGNED_UPLOAD_METADATA")
        }
    }

    private fun validateCreate(input: CreateDriveInput) {
        requireSafeId(input.clientRequestId)
        require(input.title.isNotBlank() && input.title.codePointLength() <= 40)
        require(input.summary.codePointLength() <= 160)
        require(input.distanceMeters in 0..2_000_000)
        require(input.durationSeconds in 0..259_200)
        require(input.highlightCount in 0..100)
        require(input.consentPolicyVersion.isNotBlank() && input.consentPolicyVersion.length <= 32)
        requireValidManifest(input.assets, requireBothKinds = true)
    }

    private fun requireValidManifest(assets: List<AssetManifest>, requireBothKinds: Boolean) {
        require(assets.isNotEmpty() && assets.size <= 2)
        require(assets.map(AssetManifest::kind).distinct().size == assets.size)
        if (requireBothKinds) require(assets.map(AssetManifest::kind).toSet() == AssetKind.entries.toSet())
        assets.forEach { asset ->
            when (asset.kind) {
                AssetKind.COVER -> require(asset.contentType in setOf("image/jpeg", "image/webp") && asset.sizeBytes <= 10L * 1024 * 1024)
                AssetKind.ROUTE -> require(asset.contentType == "application/json" && asset.sizeBytes <= 1024L * 1024)
            }
        }
    }

    private fun createBody(input: CreateDriveInput): JsonValue = jsonObject(
        "clientDraftId" to jsonString(input.clientRequestId),
        "schemaVersion" to jsonNumber(1),
        "title" to jsonString(input.title),
        "summary" to jsonString(input.summary),
        "visibility" to jsonString("PUBLIC"),
        "distanceMeters" to jsonNumber(input.distanceMeters),
        "durationSeconds" to jsonNumber(input.durationSeconds),
        "highlightCount" to jsonNumber(input.highlightCount),
        "consent" to jsonObject(
            "routeShare" to jsonBoolean(true),
            "coverShare" to jsonBoolean(true),
            "policyVersion" to jsonString(input.consentPolicyVersion),
        ),
        "assets" to manifestsJson(input.assets),
    )

    private fun manifestsJson(assets: List<AssetManifest>): JsonValue.Array = JsonValue.Array(assets.map { asset ->
        jsonObject(
            "kind" to jsonString(asset.kind.name),
            "contentType" to jsonString(asset.contentType),
            "sizeBytes" to jsonNumber(asset.sizeBytes),
            "sha256" to jsonString(asset.sha256Hex),
        )
    })

    private fun parseDrive(values: Map<String, JsonValue>): DriveResource {
        val id = values.string("id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{8,128}"))) throw CloudContractException("INVALID_DRIVE_ID")
        val status = enumValueOfOrContract<DriveStatus>(values.string("status"))
        val version = values.integer("version")
        val title = values.string("title")
        val summary = values.string("summary")
        val distance = values.integer("distanceMeters")
        val duration = values.integer("durationSeconds")
        val highlights = values.integer("highlightCount")
        val createdText = values.string("createdAt")
        val publishedText = values.optionalString("publishedAt")
        val created = safeTimestamp(createdText)
        val published = publishedText?.let(::safeTimestamp)
        if (version !in 1..1_000_000 || title.isBlank() || title.codePointLength() > 40 || summary.codePointLength() > 160 ||
            distance !in 0..2_000_000 || duration !in 0..259_200 || highlights !in 0..100 ||
            (status == DriveStatus.PUBLISHED && published == null) || (status == DriveStatus.DRAFT && published != null) ||
            (published != null && published < created)
        ) throw CloudContractException("INVALID_DRIVE_RESOURCE")
        return DriveResource(id, status, version, title, summary, distance, duration, highlights, createdText, publishedText)
    }

    private fun validateSigV4(instruction: UploadInstruction) {
        val algorithm = instruction.uri.queryParameter("X-Amz-Algorithm")
        val credential = instruction.uri.queryParameter("X-Amz-Credential")
        val signedAtText = instruction.uri.queryParameter("X-Amz-Date")
        val expiresText = instruction.uri.queryParameter("X-Amz-Expires")
        val signature = instruction.uri.queryParameter("X-Amz-Signature")
        if (algorithm != "AWS4-HMAC-SHA256" || credential == null || signedAtText == null || expiresText == null ||
            signature?.matches(Regex("[a-f0-9]{64}")) != true
        ) throw CloudContractException("INVALID_SIGV4")
        val credentialParts = credential.split('/')
        if (credentialParts.size != 5 || !credentialParts[0].matches(Regex("[A-Z0-9]{16,128}")) ||
            !credentialParts[1].matches(Regex("[0-9]{8}")) || !credentialParts[2].matches(Regex("[a-z0-9-]{3,32}")) ||
            credentialParts[3] != "s3" || credentialParts[4] != "aws4_request"
        ) throw CloudContractException("INVALID_SIGV4_CREDENTIAL")
        val signedAt = runCatching { SIGV4_DATE.parse(signedAtText, Instant::from) }
            .getOrElse { throw CloudContractException("INVALID_SIGV4_DATE") }
        if (credentialParts[1] != signedAtText.take(8)) throw CloudContractException("INVALID_SIGV4_CREDENTIAL")
        val expiresSeconds = expiresText.toLongOrNull()
            ?.takeIf { it in 1..MAX_PRESIGNED_SECONDS }
            ?: throw CloudContractException("INVALID_SIGV4_EXPIRY")
        val declaredExpiry = runCatching { Instant.parse(instruction.expiresAt) }
            .getOrElse { throw CloudContractException("INVALID_UPLOAD_EXPIRY") }
        val signedExpiry = signedAt.plusSeconds(expiresSeconds)
        if (declaredExpiry.isBefore(signedAt) || kotlin.math.abs(declaredExpiry.epochSecond - signedExpiry.epochSecond) > CLOCK_SKEW_SECONDS) {
            throw CloudContractException("INVALID_UPLOAD_EXPIRY")
        }
        val effectiveExpiry = if (declaredExpiry < signedExpiry) declaredExpiry else signedExpiry
        val now = clock()
        if (signedAt.isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS)) || !now.plusSeconds(CLOCK_SKEW_SECONDS).isBefore(effectiveExpiry)) {
            throw CloudDriveException(CloudFailureKind.UPLOAD_EXPIRED, "UPLOAD_SESSION_EXPIRED", retryable = true)
        }
    }

    private fun safeTimestamp(value: String): Instant {
        val instant = runCatching { Instant.parse(value) }.getOrElse { throw CloudContractException("INVALID_TIMESTAMP") }
        val year = instant.atZone(ZoneOffset.UTC).year
        if (year !in 2020..2100) throw CloudContractException("INVALID_TIMESTAMP")
        return instant
    }

    private fun apiUri(path: String): URI {
        require(path.startsWith('/') && !path.startsWith("//"))
        return URI(config.apiBaseUri.toString().trimEnd('/') + path)
    }

    private fun secureUploadUri(value: String): URI {
        val uri = secureUri(value)
        if (uri.host.lowercase() !in config.allowedUploadHosts) throw CloudContractException("UPLOAD_HOST_NOT_ALLOWED")
        return uri
    }

    private fun secureAssetUri(value: String): URI {
        val uri = secureUri(value)
        if (uri.host.lowercase() !in config.allowedUploadHosts) throw CloudContractException("ASSET_HOST_NOT_ALLOWED")
        return uri
    }

    private fun secureUri(value: String): URI {
        if (value.length !in 1..MAX_CLOUD_URL_LENGTH || '\\' in value || value.any(Char::isWhitespace)) {
            throw CloudContractException("INVALID_URL")
        }
        val uri = runCatching { URI(value) }.getOrElse { throw CloudContractException("INVALID_URL") }
        if (!uri.scheme.equals("https", true) || uri.host == null || uri.userInfo != null || uri.fragment != null ||
            uri.rawPath.isNullOrEmpty() || !uri.rawPath.startsWith('/') || (uri.port != -1 && uri.port != 443)
        ) {
            throw CloudContractException("INVALID_URL")
        }
        return uri
    }

    private fun requireSafeId(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_-]{8,128}")))
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private fun percentEncode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (character.isLetterOrDigit() || character in "-._~") append(character)
            else append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
        }
    }

    private fun sha256Hex(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it) }

    private fun hexToBase64(value: String): String {
        val bytes = ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun Map<String, String>.caseInsensitive(name: String): String? = entries
        .singleOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun URI.queryParameter(name: String): String? = rawQuery
        ?.split('&')
        ?.mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) null else percentDecode(part.substring(0, separator)) to percentDecode(part.substring(separator + 1))
        }
        ?.singleOrNull { it.first.equals(name, ignoreCase = true) }
        ?.second

    private fun percentDecode(value: String): String {
        val output = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length) throw CloudContractException("INVALID_URL")
                val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
                    ?: throw CloudContractException("INVALID_URL")
                output.write(decoded)
                index += 3
            } else {
                output.write(value[index].code)
                index += 1
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun Map<String, JsonValue>.requiredObject(name: String): Map<String, JsonValue> = get(name)?.objectValue()
        ?: throw CloudContractException("INVALID_RESPONSE")

    private inline fun <reified T : Enum<T>> enumValueOfOrContract(value: String): T = runCatching { enumValueOf<T>(value) }
        .getOrElse { throw CloudContractException("INVALID_RESPONSE") }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_CLOUD_URL_LENGTH = 8_192
        private const val MAX_PRESIGNED_SECONDS = 900L
        private const val CLOCK_SKEW_SECONDS = 30L
        private const val HEX = "0123456789ABCDEF"
        private val ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val REQUIRED_UPLOAD_HEADERS = setOf("content-type", "x-amz-checksum-sha256")
        private val SIGNED_UPLOAD_HEADER_ORDER = listOf("content-type", "host", "x-amz-checksum-sha256")
        private val SIGV4_DATE: DateTimeFormatter = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}

private fun parsePublishedRoute(bytes: ByteArray): PublishedRoute = try {
    val root = JsonCodec.parse(bytes).objectValue()
    if (root.keys != setOf("schemaVersion", "path", "highlights") || root.integer("schemaVersion") != 1) {
        throw CloudContractException("INVALID_ROUTE_SCHEMA")
    }
    val path = root.array("path")
    if (path.size !in 2..500) throw CloudContractException("INVALID_ROUTE_PATH")
    val highlights = root.array("highlights")
    if (highlights.size > 100) throw CloudContractException("INVALID_ROUTE_HIGHLIGHTS")
    highlights.forEach { value ->
        val highlight = value.objectValue()
        if (highlight.keys.any { it !in setOf("progress", "title") } || "progress" !in highlight) {
            throw CloudContractException("INVALID_ROUTE_HIGHLIGHT")
        }
        highlight.unitFloat("progress")
        val title = highlight["title"]
        if (title != null && ((title as? JsonValue.StringValue)?.value?.codePointLength() ?: 41) > 40) {
            throw CloudContractException("INVALID_ROUTE_HIGHLIGHT")
        }
    }
    PublishedRoute(path.map { value ->
        val point = value.objectValue()
        if (point.keys.any { it !in setOf("horizontal", "vertical", "progress") } ||
            !point.keys.containsAll(setOf("horizontal", "vertical"))
        ) throw CloudContractException("INVALID_ROUTE_POINT")
        PublishedRoutePoint(
            horizontal = point.unitFloat("horizontal"),
            vertical = point.unitFloat("vertical"),
            progress = point.optionalUnitFloat("progress"),
        )
    })
} catch (known: CloudDriveException) {
    throw known
} catch (_: Throwable) {
    throw CloudContractException("INVALID_ROUTE_DOCUMENT")
}

private fun Map<String, JsonValue>.unitFloat(name: String): Float =
    ((get(name) as? JsonValue.NumberValue)?.value?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?: throw CloudContractException("INVALID_ROUTE_POINT")).toFloat()

private fun Map<String, JsonValue>.optionalUnitFloat(name: String): Float? = when (get(name)) {
    null -> null
    else -> unitFloat(name)
}

private fun String.codePointLength(): Int = codePoints().count().toInt()
