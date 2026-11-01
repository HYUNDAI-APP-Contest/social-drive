package com.socialdrive.app.safety

import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.MotionState

data class SafetyDecision(val allowed: Boolean, val reason: String? = null)

object SafetyPolicy {
    private val parkedOnly = setOf(AppDestination.Detail, AppDestination.Publish, AppDestination.Settings)

    fun canNavigate(destination: AppDestination, motion: MotionState): SafetyDecision {
        if (destination !in parkedOnly) return SafetyDecision(allowed = true)
        return when (motion) {
            MotionState.Parked -> SafetyDecision(allowed = true)
            MotionState.Driving -> SafetyDecision(false, "주행 중에는 이 기능을 사용할 수 없어요.")
            MotionState.Unknown -> SafetyDecision(false, "주차 상태를 확인한 뒤 사용할 수 있어요.")
        }
    }

    fun canEdit(motion: MotionState): Boolean = motion == MotionState.Parked
}
