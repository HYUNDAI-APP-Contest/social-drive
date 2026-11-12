package com.socialdrive.app.ui

import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudDetailPresentationTest {
    private val fallback = requireNotNull(FakeDriveRepository().drive("demo-coast"))

    @Test
    fun `selected cloud detail wins only for its matching in-memory identity`() {
        val summary = fallback.copy(id = "cloud-drive-1", route = listOf(RoutePoint(.1f, .9f), RoutePoint(.9f, .1f)))
        val detailed = summary.copy(route = listOf(RoutePoint(.2f, .8f), RoutePoint(.8f, .2f)))

        assertEquals(
            detailed.route,
            selectStoryBase(summary.id, detailed, null, summary, fallback).route,
        )
        assertEquals(
            summary.route,
            selectStoryBase(summary.id, detailed.copy(id = "cloud-other"), null, summary, fallback).route,
        )
    }

    @Test
    fun `only latest request generation may update the selected cloud detail`() {
        assertEquals(
            true,
            ownsCloudDetailRequest(3, 3, "cloud-drive-1", "cloud-drive-1"),
        )
        assertEquals(
            false,
            ownsCloudDetailRequest(2, 3, "cloud-drive-1", "cloud-drive-1"),
        )
        assertEquals(
            false,
            ownsCloudDetailRequest(3, 3, "cloud-drive-1", "cloud-drive-2"),
        )
    }
}
