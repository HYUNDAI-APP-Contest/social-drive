package com.socialdrive.app.ui.screens

import com.socialdrive.app.data.fake.FakeDriveRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeDriveFilterTest {
    @Test
    fun `기기 필터는 로컬 기록만 남긴다`() {
        val demo = requireNotNull(FakeDriveRepository().drive("demo-coast"))
        val local = demo.copy(id = "local-draft-1", title = "기기 기록")

        assertEquals(listOf(local), filterDrives(listOf(local, demo), DriveFilter.Local))
    }

    @Test
    fun `기존 지역 필터는 기기 기록을 데모 지역으로 오인하지 않는다`() {
        val coast = requireNotNull(FakeDriveRepository().drive("demo-coast"))
        val local = coast.copy(id = "local-draft-1")

        assertEquals(listOf(coast), filterDrives(listOf(local, coast), DriveFilter.Coast))
    }
}
