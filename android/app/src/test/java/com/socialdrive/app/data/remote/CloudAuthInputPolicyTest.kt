package com.socialdrive.app.data.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudAuthInputPolicyTest {
    @Test
    fun `로그인 입력은 비어 있지 않은 제한 길이만 허용한다`() {
        assertTrue(CloudAuthInputPolicy.acceptsCredentials("driver@example.com", charArrayOf('a')))
        assertFalse(CloudAuthInputPolicy.acceptsCredentials(" ", charArrayOf('a')))
        assertFalse(CloudAuthInputPolicy.acceptsCredentials("a".repeat(257), charArrayOf('a')))
        assertFalse(CloudAuthInputPolicy.acceptsCredentials("driver", CharArray(257) { 'a' }))
    }

    @Test
    fun `추가 인증 응답은 메모리 상한을 적용한다`() {
        assertTrue(CloudAuthInputPolicy.acceptsChallenge(charArrayOf('1', '2', '3', '4', '5', '6')))
        assertFalse(CloudAuthInputPolicy.acceptsChallenge(charArrayOf()))
        assertFalse(CloudAuthInputPolicy.acceptsChallenge(CharArray(2_049) { 'a' }))
    }
}
