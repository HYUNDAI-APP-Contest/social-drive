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

    private fun token(
        issuer: String = "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_AbCdEf123",
        clientId: String = configuration.clientId,
        tokenUse: String = "access",
        issuedAt: Long = now.epochSecond - 60L,
        expiresAt: Long = now.epochSecond + 3_600L,
        extra: String = "",
    ): String {
        val header = encode("{\"alg\":\"RS256\",\"kid\":\"test\"}")
        val payload = encode(
            "{$extra\"iss\":\"$issuer\",\"client_id\":\"$clientId\",\"token_use\":\"$tokenUse\",\"iat\":$issuedAt,\"exp\":$expiresAt}",
        )
        return "$header.$payload.signature"
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray())

    private fun rawPayload(payload: String): String = "${encode("{\"alg\":\"RS256\"}")}.${encode(payload)}.signature"
}
