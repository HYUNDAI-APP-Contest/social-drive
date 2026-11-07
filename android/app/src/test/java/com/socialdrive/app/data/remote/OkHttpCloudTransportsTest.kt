package com.socialdrive.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OkHttpCloudTransportsTest {
    @Test
    fun `클라우드 호출은 리다이렉트와 암묵적 재시도를 차단한다`() {
        val client = createCloudHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `느린 클라우드 연결도 전체 시간 상한 안에서 종료한다`() {
        val client = createCloudHttpClient()

        assertEquals(CLOUD_CONNECT_TIMEOUT_SECONDS * 1_000, client.connectTimeoutMillis.toLong())
        assertEquals(CLOUD_IO_TIMEOUT_SECONDS * 1_000, client.readTimeoutMillis.toLong())
        assertEquals(CLOUD_IO_TIMEOUT_SECONDS * 1_000, client.writeTimeoutMillis.toLong())
        assertEquals(CLOUD_CALL_TIMEOUT_SECONDS * 1_000, client.callTimeoutMillis.toLong())
    }
}
