package com.socialdrive.app.data.remote

import com.amplifyframework.auth.result.step.AuthSignInStep
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmplifyCognitoAuthTest {
    @Test
    fun `Cognito SDK 작업은 동시에 하나만 진행한다`() {
        val gate = CognitoOperationGate()

        assertTrue(gate.tryEnter())
        assertEquals(false, gate.tryEnter())
        gate.leave()
        assertTrue(gate.tryEnter())
        gate.leave()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `백그라운드 토큰 요청은 기존 인증 작업 뒤에 순서대로 진행한다`() = runTest {
        val gate = CognitoOperationGate()
        assertTrue(gate.tryEnter())

        val waiting = async {
            gate.enter()
            gate.leave()
            true
        }
        runCurrent()
        assertEquals(false, waiting.isCompleted)

        gate.leave()
        runCurrent()
        assertTrue(waiting.await())
    }

    @Test
    fun `Cognito SDK 콜백 대기는 유한하다`() = runTest {
        assertEquals(20_000L, COGNITO_OPERATION_TIMEOUT_MILLIS)
        var timedOut = false
        try {
            withCognitoOperationTimeout(timeoutMillis = 100L) { awaitCancellation() }
        } catch (_: CognitoOperationTimeoutException) {
            timedOut = true
        }
        assertTrue(timedOut)
    }

    @Test
    fun `저장된 세션이 없으면 오류가 아닌 로그인 대기로 표시한다`() {
        assertEquals(
            CloudAuthState(CloudAuthPhase.SIGNED_OUT, "AWS 계정에 로그인해 주세요"),
            restoredCognitoSessionState(isSignedIn = false, sessionInstalled = false),
        )
    }

    @Test
    fun `유효한 저장 세션만 연결 완료로 표시한다`() {
        assertEquals(
            CloudAuthState(CloudAuthPhase.SIGNED_IN, "AWS 계정 연결됨"),
            restoredCognitoSessionState(isSignedIn = true, sessionInstalled = true),
        )
        assertEquals(
            CloudAuthPhase.ERROR,
            restoredCognitoSessionState(isSignedIn = true, sessionInstalled = false).phase,
        )
    }

    @Test
    fun `늦은 토큰 무효화는 로그인 완료 상태만 재인증으로 바꾼다`() {
        val signedIn = CloudAuthState(CloudAuthPhase.SIGNED_IN, "AWS 계정 연결됨")
        assertEquals(CloudAuthPhase.REAUTH_REQUIRED, invalidatedCognitoSessionState(signedIn).phase)

        for (phase in listOf(CloudAuthPhase.SIGNED_OUT, CloudAuthPhase.WORKING, CloudAuthPhase.CHALLENGE)) {
            val current = CloudAuthState(phase, "현재 작업 유지")
            assertEquals(current, invalidatedCognitoSessionState(current))
        }
    }

    @Test
    fun `TOTP와 문자 MFA를 사용자에게 구분해서 안내한다`() {
        assertEquals("인증 앱 코드", AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE.challengeLabel())
        assertEquals("문자 인증코드", AuthSignInStep.CONFIRM_SIGN_IN_WITH_SMS_MFA_CODE.challengeLabel())
    }

    @Test
    fun `비밀번호 재설정 단계는 잘못된 추가 인증 입력창을 열지 않는다`() {
        assertNull(AuthSignInStep.RESET_PASSWORD.challengeLabel())
        assertEquals(
            "비밀번호 재설정이 필요한 계정입니다. 관리자에게 문의해 주세요",
            AuthSignInStep.RESET_PASSWORD.unsupportedMessage(),
        )
    }

    @Test
    fun `TOTP 신규 등록 단계는 관리 경계를 명시한다`() {
        assertNull(AuthSignInStep.CONTINUE_SIGN_IN_WITH_TOTP_SETUP.challengeLabel())
        assertEquals(
            "MFA 등록이 필요한 계정입니다. 관리자에게 문의해 주세요",
            AuthSignInStep.CONTINUE_SIGN_IN_WITH_TOTP_SETUP.unsupportedMessage(),
        )
    }
}
