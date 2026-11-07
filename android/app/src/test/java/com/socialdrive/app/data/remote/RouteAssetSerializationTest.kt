package com.socialdrive.app.data.remote

import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.domain.NormalizedRoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.security.MessageDigest

class RouteAssetSerializationTest {
    @Test
    fun `대표 이미지는 피드에서 렌더링할 수 있는 유효 JPEG 자산이다`() {
        val cover = draft(emptyList()).assetUploads().single { it.kind == AssetKind.COVER }

        assertEquals("image/jpeg", cover.contentType)
        assertTrue(cover.content.size > 1_024)
        assertEquals(0xff.toByte(), cover.content[0])
        assertEquals(0xd8.toByte(), cover.content[1])
        assertEquals(0xff.toByte(), cover.content[cover.content.lastIndex - 1])
        assertEquals(0xd9.toByte(), cover.content.last())
        val checksum = MessageDigest.getInstance("SHA-256").digest(cover.content)
            .joinToString("") { "%02x".format(it) }
        assertEquals(checksum, cover.checksumSha256Hex)
    }

    @Test
    fun `정규화 경로만 업로드 문서에 직렬화한다`() {
        val draft = draft(
            listOf(
                NormalizedRoutePoint(0, .1f, .9f),
                NormalizedRoutePoint(1, .4f, .5f),
                NormalizedRoutePoint(2, .8f, .2f),
            ),
        )
        val route = draft.assetUploads().single { it.kind == AssetKind.ROUTE }.content.toString(Charsets.UTF_8)
        assertTrue(route.contains("\"horizontal\":0.1"))
        assertTrue(route.contains("\"vertical\":0.2"))
        assertFalse(route.contains("latitude", ignoreCase = true))
        assertFalse(route.contains("longitude", ignoreCase = true))
        assertFalse(route.contains("originalContentRef", ignoreCase = true))
    }

    @Test
    fun `중복 좌표나 역순 경로는 실제 기록 자산으로 표시하지 않는다`() {
        val duplicate = draft(
            listOf(NormalizedRoutePoint(0, .5f, .5f), NormalizedRoutePoint(1, .5f, .5f)),
        ).assetUploads().single { it.kind == AssetKind.ROUTE }
        val reversed = draft(
            listOf(NormalizedRoutePoint(1, .2f, .7f), NormalizedRoutePoint(0, .8f, .3f)),
        ).assetUploads().single { it.kind == AssetKind.ROUTE }

        assertTrue(duplicate.syntheticDemoContent)
        assertTrue(reversed.syntheticDemoContent)
    }

    @Test
    fun `서버 제한에 맞게 첫 점과 끝 점을 보존하며 500점으로 축약한다`() {
        val points = List(1_001) { index -> NormalizedRoutePoint(index, index / 1_000f, 1f - index / 1_000f) }
        val route = draft(points).assetUploads().single { it.kind == AssetKind.ROUTE }.content
        val root = JsonCodec.parse(route).objectValue()
        val path = root.array("path")
        assertEquals(500, path.size)
        assertEquals(0.0, (path.first().objectValue().getValue("horizontal") as JsonValue.NumberValue).value.toDouble())
        assertEquals(1.0, (path.last().objectValue().getValue("horizontal") as JsonValue.NumberValue).value.toDouble())
    }

    private fun draft(points: List<NormalizedRoutePoint>) = DraftSnapshot(
        id = "draft", recordingId = "recording", clientRequestId = "drive-request",
        title = "제목", description = "설명", coverPreset = "cover",
        originalContentRef = "local-recording://private", state = "DRAFT", routePoints = points,
    )
}
