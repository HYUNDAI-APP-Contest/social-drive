package com.socialdrive.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidatingCognitoSessionSourceTest {
    @Test
    fun `매 요청마다 새 토큰을 검증하고 상태를 갱신한다`() = kotlinx.coroutines.test.runTest {
        val tokens = ArrayDeque(listOf("expired", "fresh"))
        val states = mutableListOf<Boolean>()
        val source = ValidatingCognitoSessionSource(
            delegate = CognitoSessionSource { tokens.removeFirst() },
            validator = AccessTokenValidator { it == "fresh" },
            onValidation = states::add,
        )

        assertFailsWith<IllegalArgumentException> { source.freshAccessToken() }
        assertEquals("fresh", source.freshAccessToken())
        assertEquals(listOf(false, true), states)
    }

    @Test
    fun `세션 공급자 예외도 로그인 갱신 필요 상태로 전달한다`() = kotlinx.coroutines.test.runTest {
        val states = mutableListOf<Boolean>()
        var invalidated = false
        val source = ValidatingCognitoSessionSource(
            delegate = object : CognitoSessionSource {
                override suspend fun freshAccessToken(): String = error("signed out")
                override fun onSessionInvalidated() { invalidated = true }
            },
            validator = AccessTokenValidator { true },
            onValidation = states::add,
        )

        assertFailsWith<IllegalStateException> { source.freshAccessToken() }
        assertEquals(listOf(false), states)
        assertTrue(invalidated)
    }

    @Test
    fun `일시적인 토큰 갱신 장애는 유효한 세션을 무효화하지 않는다`() = kotlinx.coroutines.test.runTest {
        val states = mutableListOf<Boolean>()
        var invalidated = false
        val source = ValidatingCognitoSessionSource(
            delegate = object : CognitoSessionSource {
                override suspend fun freshAccessToken(): String =
                    throw AuthSessionTemporarilyUnavailableException()

                override fun onSessionInvalidated() { invalidated = true }
            },
            validator = AccessTokenValidator { true },
            onValidation = states::add,
        )

        assertFailsWith<AuthSessionTemporarilyUnavailableException> { source.freshAccessToken() }
        assertEquals(emptyList(), states)
        assertEquals(false, invalidated)
    }

    @Test
    fun `거부된 토큰은 SDK 인증 상태도 재인증 필요로 무효화한다`() = kotlinx.coroutines.test.runTest {
        var invalidated = false
        val source = ValidatingCognitoSessionSource(
            delegate = object : CognitoSessionSource {
                override suspend fun freshAccessToken() = "wrong-pool-token"
                override fun onSessionInvalidated() { invalidated = true }
            },
            validator = AccessTokenValidator { false },
            onValidation = {},
        )

        assertFailsWith<IllegalArgumentException> { source.freshAccessToken() }
        assertTrue(invalidated)
    }

    @Test
    fun `정상적인 코루틴 취소는 로그인 실패로 표시하지 않는다`() = kotlinx.coroutines.test.runTest {
        val states = mutableListOf<Boolean>()
        val source = ValidatingCognitoSessionSource(
            delegate = CognitoSessionSource { throw CancellationException("screen closed") },
            validator = AccessTokenValidator { true },
            onValidation = states::add,
        )

        assertFailsWith<CancellationException> { source.freshAccessToken() }
        assertEquals(emptyList(), states)
    }

    @Test
    fun `최초 세션 검증도 코루틴 취소를 삼키지 않는다`() = kotlinx.coroutines.test.runTest {
        val cancelled = CognitoSessionSource { throw CancellationException("login closed") }

        assertFailsWith<CancellationException> {
            validateCognitoSession(cancelled, AccessTokenValidator { true })
        }
    }

    @Test
    fun `최초 세션 검증은 토큰 오류와 공급자 오류를 안전하게 거부한다`() = kotlinx.coroutines.test.runTest {
        assertEquals(
            false,
            validateCognitoSession(CognitoSessionSource { "invalid" }, AccessTokenValidator { false }),
        )
        assertEquals(
            false,
            validateCognitoSession(CognitoSessionSource { error("signed out") }, AccessTokenValidator { true }),
        )
    }

    @Test
    fun `만료된 세션 임대는 토큰 조회 전과 완료 후 모두 거부한다`() = kotlinx.coroutines.test.runTest {
        var current = false
        var requests = 0
        val before = ValidatingCognitoSessionSource(
            delegate = CognitoSessionSource { requests += 1; "token" },
            validator = AccessTokenValidator { true },
            onValidation = {},
            isCurrent = { current },
        )
        assertFailsWith<IllegalStateException> { before.freshAccessToken() }
        assertEquals(0, requests)

        current = true
        val requestStarted = CompletableDeferred<Unit>()
        val tokenReady = CompletableDeferred<String>()
        val after = ValidatingCognitoSessionSource(
            delegate = CognitoSessionSource {
                requestStarted.complete(Unit)
                tokenReady.await()
            },
            validator = AccessTokenValidator { true },
            onValidation = {},
            isCurrent = { current },
        )
        supervisorScope {
            val request = async { after.freshAccessToken() }
            requestStarted.await()
            current = false
            tokenReady.complete("token")
            assertFailsWith<IllegalStateException> { request.await() }
        }
    }

    @Test
    fun `검증 중 교체된 세션은 토큰과 상태를 게시하지 않는다`() = kotlinx.coroutines.test.runTest {
        var current = true
        val states = mutableListOf<Boolean>()
        var invalidated = false
        val source = ValidatingCognitoSessionSource(
            delegate = object : CognitoSessionSource {
                override suspend fun freshAccessToken() = "token"
                override fun onSessionInvalidated() { invalidated = true }
            },
            validator = AccessTokenValidator {
                current = false
                true
            },
            onValidation = states::add,
            isCurrent = { current },
        )

        assertFailsWith<IllegalStateException> { source.freshAccessToken() }
        assertEquals(emptyList(), states)
        assertEquals(false, invalidated)
    }

    @Test
    fun `토큰 조회 실패 전에 교체된 세션은 새 인증 상태를 무효화하지 않는다`() = kotlinx.coroutines.test.runTest {
        var current = true
        var invalidated = false
        val requestStarted = CompletableDeferred<Unit>()
        val failRequest = CompletableDeferred<Unit>()
        val source = ValidatingCognitoSessionSource(
            delegate = object : CognitoSessionSource {
                override suspend fun freshAccessToken(): String {
                    requestStarted.complete(Unit)
                    failRequest.await()
                    error("old session failed")
                }

                override fun onSessionInvalidated() { invalidated = true }
            },
            validator = AccessTokenValidator { true },
            onValidation = {},
            isCurrent = { current },
        )

        supervisorScope {
            val request = async { source.freshAccessToken() }
            requestStarted.await()
            current = false
            failRequest.complete(Unit)
            assertFailsWith<IllegalStateException> { request.await() }
        }
        assertEquals(false, invalidated)
    }

    @Test
    fun `서버가 거부한 현재 세션만 SDK와 연결 상태에서 무효화한다`() {
        var current = true
        var delegateInvalidations = 0
        val states = mutableListOf<Boolean>()
        val source = ValidatingCognitoSessionSource(
            delegate = object : CognitoSessionSource {
                override suspend fun freshAccessToken() = "token"
                override fun onSessionInvalidated() { delegateInvalidations += 1 }
            },
            validator = AccessTokenValidator { true },
            onValidation = states::add,
            isCurrent = { current },
        )

        source.onSessionInvalidated()
        assertEquals(1, delegateInvalidations)
        assertEquals(listOf(false), states)

        current = false
        source.onSessionInvalidated()
        assertEquals(1, delegateInvalidations)
        assertEquals(listOf(false), states)
    }
}
