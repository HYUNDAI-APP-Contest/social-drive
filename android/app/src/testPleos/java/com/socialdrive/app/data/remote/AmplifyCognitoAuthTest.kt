package com.socialdrive.app.data.remote

import com.amplifyframework.auth.result.step.AuthSignInStep
import org.junit.Assert.assertEquals
import org.junit.Test

class AmplifyCognitoAuthTest {
    @Test
    fun `TOTP와 문자 MFA를 사용자에게 구분해서 안내한다`() {
        assertEquals("인증 앱 코드", AuthSignInStep.CONFIRM_SIGN_IN_WITH_TOTP_CODE.challengeLabel())
        assertEquals("문자 인증코드", AuthSignInStep.CONFIRM_SIGN_IN_WITH_SMS_MFA_CODE.challengeLabel())
    }

    @Test
    fun `알 수 없는 다음 단계는 민감정보 없이 일반 안내한다`() {
        assertEquals("Cognito 추가 인증 응답", AuthSignInStep.RESET_PASSWORD.challengeLabel())
    }
}
