package com.socialdrive.app.data.remote

import java.nio.charset.StandardCharsets
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
        val payload = runCatching {
            Base64.getUrlDecoder().decode(parts[1]).toString(StandardCharsets.UTF_8)
        }.getOrNull() ?: return false
        if (payload.length > MAX_PAYLOAD_LENGTH) return false

        val expectedIssuer = "https://cognito-idp.${configuration.region}.amazonaws.com/${configuration.userPoolId}"
        val issuer = payload.singleStringClaim("iss") ?: return false
        val clientId = payload.singleStringClaim("client_id") ?: return false
        val tokenUse = payload.singleStringClaim("token_use") ?: return false
        val expiresAt = payload.singleLongClaim("exp") ?: return false
        val issuedAt = payload.singleLongClaim("iat") ?: return false
        val now = clock().epochSecond
        return issuer == expectedIssuer &&
            clientId == configuration.clientId &&
            tokenUse == "access" &&
            expiresAt > now + EXPIRY_SAFETY_SECONDS &&
            issuedAt <= now + CLOCK_SKEW_SECONDS &&
            issuedAt < expiresAt
    }

    private fun String.singleStringClaim(name: String): String? =
        Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .findAll(this).map { it.groupValues[1] }.toList().singleOrNull()

    private fun String.singleLongClaim(name: String): Long? =
        Regex("\\\"$name\\\"\\s*:\\s*(\\d{1,12})(?=\\s*[,}])")
            .findAll(this).mapNotNull { it.groupValues[1].toLongOrNull() }.toList().singleOrNull()

    private companion object {
        const val MAX_TOKEN_LENGTH = 16_384
        const val MAX_PAYLOAD_LENGTH = 8_192
        const val EXPIRY_SAFETY_SECONDS = 30L
        const val CLOCK_SKEW_SECONDS = 300L
    }
}
