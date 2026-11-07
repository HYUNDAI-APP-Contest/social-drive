package com.socialdrive.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudRuntimeConfigurationTest {
    @Test
    fun `승인된 HTTPS 구성만 파싱한다`() {
        val config = CloudRuntimeConfiguration.parse(
            "https://api.example.com/v1",
            "upload-a.example.com, upload-b.example.com",
            "2026-11-07",
            "ap-northeast-2",
            "ap-northeast-2_AbCdEf123",
            "1234567890abcdefghijklmnop",
        )
        assertEquals("api.example.com", config?.client?.apiBaseUri?.host)
        assertEquals(setOf("upload-a.example.com", "upload-b.example.com"), config?.client?.allowedUploadHosts)
        assertEquals("ap-northeast-2_AbCdEf123", config?.cognito?.userPoolId)
    }

    @Test
    fun `빈 값과 비 HTTPS 및 잘못된 호스트는 거부한다`() {
        assertNull(valid(apiBaseUrl = ""))
        assertNull(valid(apiBaseUrl = "http://api.example.com"))
        assertNull(valid(apiBaseUrl = "https://127.0.0.1/prod"))
        assertNull(valid(apiBaseUrl = "https://localhost/prod"))
        assertNull(valid(apiBaseUrl = "https://api..example.com/prod"))
        assertNull(valid(uploadHosts = "https://upload.example.com"))
        assertNull(valid(uploadHosts = "Upload.example.com"))
        assertNull(valid(uploadHosts = "upload..example.com"))
        assertNull(valid(uploadHosts = "-upload.example.com"))
        assertNull(valid(uploadHosts = "upload.example-.com"))
        assertNull(valid(uploadHosts = "127.0.0.1"))
    }

    @Test
    fun `Cognito 공개 식별자의 형식과 리전 일치를 검증한다`() {
        assertNull(valid(cognitoRegion = "us-east-1"))
        assertNull(valid(cognitoUserPoolId = "ap-northeast-2_short"))
        assertNull(valid(cognitoClientId = "secret-is-not-a-client-id"))
    }

    private fun valid(
        apiBaseUrl: String = "https://api.example.com/prod",
        uploadHosts: String = "upload.example.com",
        cognitoRegion: String = "ap-northeast-2",
        cognitoUserPoolId: String = "ap-northeast-2_AbCdEf123",
        cognitoClientId: String = "1234567890abcdefghijklmnop",
    ) = CloudRuntimeConfiguration.parse(
        apiBaseUrl,
        uploadHosts,
        "2026-11-07",
        cognitoRegion,
        cognitoUserPoolId,
        cognitoClientId,
    )
}
