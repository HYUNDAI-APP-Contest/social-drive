package com.socialdrive.app.data.remote

import java.net.URI
import kotlinx.coroutines.CancellationException

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

/** A transport value that deliberately redacts URL, headers, and body from diagnostics. */
class HttpRequest(
    val method: HttpMethod,
    val uri: URI,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    override fun toString(): String = "HttpRequest(method=$method, sensitiveFields=[REDACTED])"
}

/** A transport value that deliberately redacts response headers and body from diagnostics. */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
) {
    override fun toString(): String = "HttpResponse(status=$status, sensitiveFields=[REDACTED])"
}

/**
 * Cancellation-cooperative transport. Implementations must disable redirects and logging, stream
 * with a hard 2 MiB response cap, and cancel the underlying call when the coroutine is cancelled.
 */
fun interface ApiHttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * Cancellation-cooperative, no-redirect/no-log transport dedicated to presigned object requests.
 * It must never buffer more than 2 MiB of a response body or add ambient authentication headers.
 */
fun interface ObjectUploadTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class BearerToken private constructor(private val value: String) {
    internal fun authorizationHeader(): String = "Bearer $value"
    override fun toString(): String = "BearerToken([REDACTED])"

    companion object {
        fun inMemory(value: String): BearerToken {
            require(value.isNotBlank() && value.none(Char::isWhitespace)) { "Invalid bearer token" }
            return BearerToken(value)
        }
    }
}

/** Must obtain a current session for every API operation; implementations must not persist tokens. */
fun interface AuthSessionProvider {
    suspend fun freshBearerToken(): BearerToken
}

/** SDK boundary for a future configured Cognito client. No Cognito SDK or client secret is embedded here. */
fun interface CognitoSessionSource {
    suspend fun freshAccessToken(): String
    fun onSessionInvalidated() = Unit
}

internal class ValidatingCognitoSessionSource(
    private val delegate: CognitoSessionSource,
    private val validator: AccessTokenValidator,
    private val onValidation: (Boolean) -> Unit,
    private val isCurrent: () -> Boolean = { true },
) : CognitoSessionSource {
    override suspend fun freshAccessToken(): String {
        requireCurrentSession()
        val token = try {
            delegate.freshAccessToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onValidation(false)
            runCatching { delegate.onSessionInvalidated() }
            throw error
        }
        requireCurrentSession()
        val valid = runCatching { validator.isValid(token) }.getOrDefault(false)
        onValidation(valid)
        if (!valid) runCatching { delegate.onSessionInvalidated() }
        require(valid) { "Invalid Cognito access token" }
        return token
    }

    private fun requireCurrentSession() {
        if (!isCurrent()) {
            onValidation(false)
            throw IllegalStateException("Cognito session lease expired")
        }
    }
}

fun interface AccessTokenValidator {
    fun isValid(token: String): Boolean
}

internal suspend fun validateCognitoSession(
    source: CognitoSessionSource,
    validator: AccessTokenValidator,
): Boolean = try {
    validator.isValid(source.freshAccessToken())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    false
}

class InMemoryCognitoAuthSessionProvider(
    private val source: CognitoSessionSource,
    private val validator: AccessTokenValidator = AccessTokenValidator { true },
) : AuthSessionProvider {
    override suspend fun freshBearerToken(): BearerToken {
        val token = source.freshAccessToken()
        require(validator.isValid(token)) { "Invalid Cognito access token" }
        return BearerToken.inMemory(token)
    }
}

enum class DriveStatus { DRAFT, PUBLISHED }
enum class AssetKind { COVER, ROUTE }

data class AssetManifest(
    val kind: AssetKind,
    val contentType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
) {
    init {
        require(sizeBytes > 0)
        require(sha256Hex.matches(Regex("[a-f0-9]{64}")))
        require(contentType.isNotBlank())
    }
}

data class CreateDriveInput(
    val clientRequestId: String,
    val title: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val highlightCount: Int,
    val consentPolicyVersion: String,
    val assets: List<AssetManifest>,
)

data class DriveResource(
    val id: String,
    val status: DriveStatus,
    val version: Int,
    val title: String,
    val summary: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val highlightCount: Int,
    val createdAt: String,
    val publishedAt: String?,
)

class OpaqueFeedCursor private constructor(private val value: String) {
    internal fun rawValue(): String = value
    override fun toString(): String = "OpaqueFeedCursor([REDACTED])"

    companion object {
        internal fun fromServer(value: String): OpaqueFeedCursor {
            if (!value.matches(Regex("[A-Za-z0-9_-]{32,128}"))) throw CloudContractException("INVALID_CURSOR")
            return OpaqueFeedCursor(value)
        }
    }
}

/** Short-lived read links are held in memory and are always redacted from diagnostics. */
class EphemeralAssetLinks internal constructor(
    val coverUri: URI,
    val routeUri: URI?,
) {
    override fun toString(): String = "EphemeralAssetLinks([REDACTED])"
}

data class FeedItem(val drive: DriveResource, val links: EphemeralAssetLinks)
data class FeedPage(val items: List<FeedItem>, val nextCursor: OpaqueFeedCursor?)
data class DriveDetail(val drive: DriveResource, val links: EphemeralAssetLinks, val routeSchemaVersion: Int)
data class PublishedRoutePoint(val horizontal: Float, val vertical: Float, val progress: Float?)
data class PublishedRoute(val points: List<PublishedRoutePoint>)
data class DrivePatch(val expectedVersion: Int, val title: String? = null, val summary: String? = null)

class UploadInstruction internal constructor(
    val kind: AssetKind,
    val method: HttpMethod,
    val uri: URI,
    val requiredHeaders: Map<String, String>,
    val maxBytes: Long,
    val expiresAt: String,
) {
    override fun toString(): String = "UploadInstruction(kind=$kind, sensitiveFields=[REDACTED])"
}

data class CreateDriveResult(val drive: DriveResource, val uploads: List<UploadInstruction>)
data class UploadSession(val drive: DriveResource, val uploads: List<UploadInstruction>)

enum class CloudFailureKind {
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    IDEMPOTENCY_CONFLICT,
    VERSION_CONFLICT,
    UPLOAD_INCOMPLETE,
    UPLOAD_EXPIRED,
    CONSENT_RENEWAL_REQUIRED,
    VALIDATION,
    RETRYABLE,
    CONTRACT,
    UNKNOWN,
}

open class CloudDriveException(
    val kind: CloudFailureKind,
    val code: String,
    val retryable: Boolean,
    val requestId: String? = null,
) : RuntimeException(code) {
    override fun toString(): String = "CloudDriveException(kind=$kind, code=$code, retryable=$retryable)"
}

internal class CloudContractException(code: String) :
    CloudDriveException(CloudFailureKind.CONTRACT, code, retryable = false)

data class CloudDriveClientConfig(
    val apiBaseUri: URI,
    val allowedUploadHosts: Set<String>,
) {
    init {
        require(apiBaseUri.scheme.equals("https", ignoreCase = true))
        require(apiBaseUri.userInfo == null && apiBaseUri.query == null && apiBaseUri.fragment == null)
        require(apiBaseUri.host != null && (apiBaseUri.port == -1 || apiBaseUri.port == 443))
        require(allowedUploadHosts.isNotEmpty())
        require(allowedUploadHosts.all { it.isNotBlank() && it == it.lowercase() })
    }
}
