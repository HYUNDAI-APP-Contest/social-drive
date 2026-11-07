package com.socialdrive.app.data.remote

import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CognitoAccessTokenPolicyTest {
    private val now = Instant.ofEpochSecond(2_000_000_000L)
    private val configuration = CognitoRuntimeConfiguration(
        region = "ap-northeast-2",
        userPoolId = "ap-northeast-2_AbCdEf123",
        clientId = "1234567890abcdefghijklmnop",
    )
    private val policy = CognitoAccessTokenPolicy(configuration) { now }

    @Test
    fun `승인된 풀과 클라이언트의 유효한 access token만 허용한다`() {
        assertTrue(policy.isValid(token()))
        assertTrue(policy.isValid(token(keyId = "AbC+/example_01-=")))
        assertFalse(policy.isValid(token(algorithm = "none")))
        assertFalse(policy.isValid(token(keyId = "bad key id")))
        assertFalse(policy.isValid(token(keyId = "x".repeat(129))))
        assertFalse(policy.isValid(token(clientId = "abcdefghijklmnopqrstuvwxyz")))
        assertFalse(policy.isValid(token(issuer = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf123")))
        assertFalse(policy.isValid(token(tokenUse = "id")))
    }

    @Test
    fun `만료 임박 미래 발급 중복 claim과 깨진 JWT를 거부한다`() {
        assertFalse(policy.isValid(token(expiresAt = now.epochSecond + 30L)))
        assertFalse(policy.isValid(token(issuedAt = now.epochSecond + 301L)))
        assertFalse(policy.isValid(token(extra = "\"client_id\":\"${configuration.clientId}\",")))
        assertFalse(policy.isValid("not-a-jwt"))
        assertFalse(policy.isValid(rawPayload("{not-json}")))
        assertFalse(policy.isValid(rawPayload("{\"iss\":\"x\",\"iss\":\"x\"}")))
    }

    @Test
    fun `JWT 세 구간은 패딩 없는 base64url 형식과 제한된 서명을 요구한다`() {
        val valid = token()
        val parts = valid.split('.')

        assertFalse(policy.isValid("${parts[0]}=.${parts[1]}.${parts[2]}"))
        assertFalse(policy.isValid("${parts[0]}.${parts[1]}=.${parts[2]}"))
        assertFalse(policy.isValid("${parts[0]}.${parts[1]}.bad+signature"))
        assertFalse(policy.isValid("${parts[0]}.${parts[1]}.${encodeBytes(ByteArray(1_025))}"))
    }

    private fun token(
        issuer: String = "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_AbCdEf123",
        clientId: String = configuration.clientId,
        tokenUse: String = "access",
        issuedAt: Long = now.epochSecond - 60L,
        expiresAt: Long = now.epochSecond + 3_600L,
        extra: String = "",
        algorithm: String = "RS256",
        keyId: String = "1234example=",
    ): String {
        val header = encode("{\"alg\":\"$algorithm\",\"kid\":\"$keyId\"}")
        val payload = encode(
            "{$extra\"iss\":\"$issuer\",\"client_id\":\"$clientId\",\"token_use\":\"$tokenUse\",\"iat\":$issuedAt,\"exp\":$expiresAt}",
        )
        return "$header.$payload.${encodeBytes(ByteArray(256) { it.toByte() })}"
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray())

    private fun encodeBytes(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value)

    private fun rawPayload(payload: String): String =
        "${encode("{\"alg\":\"RS256\",\"kid\":\"1234example=\"}")}.${encode(payload)}.${encodeBytes(ByteArray(256) { it.toByte() })}"
}
