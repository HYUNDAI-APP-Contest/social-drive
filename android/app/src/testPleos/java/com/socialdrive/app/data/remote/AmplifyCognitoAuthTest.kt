package com.socialdrive.app.data.remote

import com.amplifyframework.auth.result.step.AuthSignInStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmplifyCognitoAuthTest {
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
