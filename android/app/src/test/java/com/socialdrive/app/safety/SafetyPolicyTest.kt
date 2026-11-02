package com.socialdrive.app.safety

import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.MotionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyPolicyTest {
    @Test
    fun `parked mode permits editing destinations`() {
        assertTrue(SafetyPolicy.canNavigate(AppDestination.Publish, MotionState.Parked).allowed)
        assertTrue(SafetyPolicy.canEdit(MotionState.Parked))
    }

    @Test
    fun `driving mode blocks detail publish and settings`() {
        listOf(AppDestination.Detail, AppDestination.Publish, AppDestination.Settings).forEach {
            assertFalse(SafetyPolicy.canNavigate(it, MotionState.Driving).allowed)
        }
    }

    @Test
    fun `unknown motion fails closed while recording remains available`() {
        assertFalse(SafetyPolicy.canNavigate(AppDestination.Publish, MotionState.Unknown).allowed)
        assertFalse(SafetyPolicy.canEdit(MotionState.Unknown))
        assertTrue(SafetyPolicy.canNavigate(AppDestination.Recording, MotionState.Unknown).allowed)
    }
}
