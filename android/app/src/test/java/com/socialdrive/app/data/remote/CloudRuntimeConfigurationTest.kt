package com.socialdrive.app.data.remote

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CloudRuntimeConfigurationTest {
    @Test
    fun `하위 클라이언트 구성도 공개 DNS 호스트만 허용한다`() {
        assertFailsWith<IllegalArgumentException> {
            CloudDriveClientConfig(URI("https://localhost/prod"), setOf("upload.example.com"))
        }
        assertFailsWith<IllegalArgumentException> {
            CloudDriveClientConfig(URI("https://api.example.com/prod"), setOf("127.0.0.1"))
        }
        assertFailsWith<IllegalArgumentException> {
            CloudDriveClientConfig(URI("https://api.example.com/prod"), setOf("upload.example.com/path"))
        }
    }

    @Test
    fun `승인된 HTTPS 구성만 파싱한다`() {
        val config = CloudRuntimeConfiguration.parse(
            "https://api.example.com/v1",
            "upload-a.example.com, upload-b.example.com",
            "2026-11-07.v1",
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
        assertNull(valid(apiBaseUrl = " https://api.example.com/prod"))
        assertNull(valid(apiBaseUrl = "https://api.example.com/prod "))
        assertNull(valid(apiBaseUrl = "https://api.example.com\\prod"))
        assertNull(valid(apiBaseUrl = "https://user@api.example.com/prod"))
        assertNull(valid(apiBaseUrl = "https://api.example.com:8443/prod"))
        assertNull(valid(apiBaseUrl = "https://api.example.com/prod?stage=dev"))
        assertNull(valid(apiBaseUrl = "https://api.example.com/prod#fragment"))
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

    @Test
    fun `앱에서 동의한 정책과 다른 클라우드 정책은 거부한다`() {
        assertNull(valid(consentPolicyVersion = "2026-11-07"))
        assertNull(valid(consentPolicyVersion = "2026-11-08.v1"))
    }

    private fun valid(
        apiBaseUrl: String = "https://api.example.com/prod",
        uploadHosts: String = "upload.example.com",
        cognitoRegion: String = "ap-northeast-2",
        cognitoUserPoolId: String = "ap-northeast-2_AbCdEf123",
        cognitoClientId: String = "1234567890abcdefghijklmnop",
        consentPolicyVersion: String = "2026-11-07.v1",
    ) = CloudRuntimeConfiguration.parse(
        apiBaseUrl,
        uploadHosts,
        consentPolicyVersion,
        cognitoRegion,
        cognitoUserPoolId,
        cognitoClientId,
    )
}
