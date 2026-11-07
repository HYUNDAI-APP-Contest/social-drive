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
        )
        assertEquals("api.example.com", config?.client?.apiBaseUri?.host)
        assertEquals(setOf("upload-a.example.com", "upload-b.example.com"), config?.client?.allowedUploadHosts)
    }

    @Test
    fun `빈 값과 비 HTTPS 및 잘못된 호스트는 거부한다`() {
        assertNull(CloudRuntimeConfiguration.parse("", "upload.example.com", "v1"))
        assertNull(CloudRuntimeConfiguration.parse("http://api.example.com", "upload.example.com", "v1"))
        assertNull(CloudRuntimeConfiguration.parse("https://api.example.com", "https://upload.example.com", "v1"))
    }
}
