package com.socialdrive.app.data.remote

import java.net.URI
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class OkHttpCloudTransportsTest {
    @Test
    fun `응답 상한은 기본 2 MiB이며 절대 10 MiB를 넘을 수 없다`() {
        val request = HttpRequest(HttpMethod.GET, URI("https://api.example.com/test"), emptyMap(), null)

        assertEquals(DEFAULT_CLOUD_RESPONSE_BYTES, request.maxResponseBytes)
        assertFailsWith<IllegalArgumentException> {
            HttpRequest(
                HttpMethod.GET,
                URI("https://api.example.com/test"),
                emptyMap(),
                null,
                maxResponseBytes = MAX_CLOUD_RESPONSE_BYTES + 1,
            )
        }
    }

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

    @Test
    fun `클라우드 전송 연결 풀은 프로세스에서 하나만 재사용한다`() {
        assertSame(sharedCloudTransports(), sharedCloudTransports())
    }
}
