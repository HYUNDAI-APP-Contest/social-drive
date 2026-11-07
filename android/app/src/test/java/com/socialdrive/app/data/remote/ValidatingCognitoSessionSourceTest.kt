package com.socialdrive.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val source = ValidatingCognitoSessionSource(
            delegate = CognitoSessionSource { error("signed out") },
            validator = AccessTokenValidator { true },
            onValidation = states::add,
        )

        assertFailsWith<IllegalStateException> { source.freshAccessToken() }
        assertEquals(listOf(false), states)
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
}
