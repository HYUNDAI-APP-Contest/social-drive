package com.socialdrive.app.data.fake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeDriveRepositoryTest {
    @Test
    fun `preset drives are deterministic and labeled as demo content`() {
        val repository = FakeDriveRepository()
        assertTrue(repository.drives().isNotEmpty())
        assertTrue(repository.drives().all { it.date.contains("데모 경로") })
        assertEquals(repository.drives().first(), repository.drive("demo-coast"))
        assertEquals(repository.drives().size, repository.drives().map { it.route }.distinct().size)
        assertTrue(repository.drives().flatMap { it.route }.all { point ->
            point.horizontal in 0f..1f && point.vertical in 0f..1f
        })
    }
}
