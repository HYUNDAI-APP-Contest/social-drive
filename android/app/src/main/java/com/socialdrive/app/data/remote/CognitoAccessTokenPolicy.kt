package com.socialdrive.app.data.remote

import java.time.Instant
import java.util.Base64

/**
 * Validates non-secret Cognito access-token claims before a token can reach the HTTP boundary.
 * Signature verification remains the API Gateway authorizer's responsibility.
 */
class CognitoAccessTokenPolicy(
    private val configuration: CognitoRuntimeConfiguration,
    private val clock: () -> Instant = Instant::now,
) : AccessTokenValidator {
    override fun isValid(token: String): Boolean {
        if (token.length !in 32..MAX_TOKEN_LENGTH || token.any(Char::isWhitespace)) return false
        val parts = token.split('.')
        if (parts.size != 3 || parts.any(String::isEmpty)) return false
        val headerBytes = runCatching {
            Base64.getUrlDecoder().decode(parts[0])
        }.getOrNull() ?: return false
        if (headerBytes.size > MAX_HEADER_LENGTH) return false
        val header = runCatching { JsonCodec.parse(headerBytes).objectValue() }.getOrNull() ?: return false
        if (header.stringClaim("alg") != "RS256") return false
        val keyId = header.stringClaim("kid") ?: return false
        if (!KEY_ID_PATTERN.matches(keyId)) return false
        val payloadBytes = runCatching {
            Base64.getUrlDecoder().decode(parts[1])
        }.getOrNull() ?: return false
        if (payloadBytes.size > MAX_PAYLOAD_LENGTH) return false
        val claims = runCatching { JsonCodec.parse(payloadBytes).objectValue() }.getOrNull() ?: return false

        val expectedIssuer = "https://cognito-idp.${configuration.region}.amazonaws.com/${configuration.userPoolId}"
        val issuer = claims.stringClaim("iss") ?: return false
        val clientId = claims.stringClaim("client_id") ?: return false
        val tokenUse = claims.stringClaim("token_use") ?: return false
        val expiresAt = claims.longClaim("exp") ?: return false
        val issuedAt = claims.longClaim("iat") ?: return false
        val now = clock().epochSecond
        return issuer == expectedIssuer &&
            clientId == configuration.clientId &&
            tokenUse == "access" &&
            expiresAt > now + EXPIRY_SAFETY_SECONDS &&
            issuedAt <= now + CLOCK_SKEW_SECONDS &&
            issuedAt < expiresAt
    }

    private fun Map<String, JsonValue>.stringClaim(name: String): String? =
        (get(name) as? JsonValue.StringValue)?.value

    private fun Map<String, JsonValue>.longClaim(name: String): Long? =
        (get(name) as? JsonValue.NumberValue)?.value?.takeIf { it.length in 1..12 }?.toLongOrNull()

    private companion object {
        const val MAX_TOKEN_LENGTH = 16_384
        const val MAX_HEADER_LENGTH = 1_024
        const val MAX_PAYLOAD_LENGTH = 8_192
        const val EXPIRY_SAFETY_SECONDS = 30L
        const val CLOCK_SKEW_SECONDS = 300L
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
