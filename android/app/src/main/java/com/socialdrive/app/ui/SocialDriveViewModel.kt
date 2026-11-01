package com.socialdrive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.socialdrive.app.SocialDriveApplication
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.local.RecordingEntity
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.domain.PublishedDeleteDecision
import com.socialdrive.app.domain.RouteConsentDecision
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.WithdrawalReceipt
import com.socialdrive.app.domain.VehicleMotionStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SocialDriveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SocialDriveApplication

    val activeRecording: StateFlow<RecordingEntity?> = app.recordingRepository.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestDraft: StateFlow<DraftSnapshot?> = app.draftRepository.drafts
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uploadState: StateFlow<UploadState> = latestDraft
        .flatMapLatest { draft -> draft?.let { app.publishQueueRepository.observeState(it.id) } ?: flowOf(UploadState.Draft) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadState.Draft)

    val vehicleMotion: StateFlow<VehicleMotionStatus> = app.vehicleMotionSource.status
    val navigationState: StateFlow<NavigationState> = app.navigationGateway.state
    val navigationCapability = app.navigationGateway.capability

    val routePrivacy: StateFlow<RoutePrivacyStatus> = app.routePrivacyRepository.status
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            RoutePrivacyStatus(
                RouteSource.DemoPreset,
                RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON),
                RouteConsentState.NotAsked,
            ),
        )

    val deletePending: StateFlow<Boolean> = latestDraft
        .flatMapLatest { draft ->
            draft?.let { app.database.routePrivacyDao().observeDeleteRequest(it.id).map { request -> request != null } }
                ?: flowOf(false)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun startRecording() = app.recordingRepository.start()

    suspend fun addHighlight() {
        activeRecording.value?.let { app.recordingRepository.addHighlight(it.id) }
    }

    suspend fun finishActiveRecording(): DraftSnapshot? = app.recordingRepository.finishActiveAsDraft()

    suspend fun updateDraftCopy(title: String, description: String): Boolean =
        latestDraft.value?.let { app.draftRepository.updateCopy(it.id, title, description) } ?: false

    suspend fun enqueuePublish(motionState: MotionState): EnqueueResult =
        latestDraft.value?.let { app.publishScheduler.enqueue(it.id, motionState) } ?: EnqueueResult.MissingDraft

    suspend fun requestDemoMotion(motionState: MotionState): Boolean =
        app.vehicleMotionSource.requestSimulation(motionState)

    suspend fun requestNavigation(request: NavigationRequest): NavigationRequestResult {
        if (vehicleMotion.value.motion != MotionState.Parked) {
            return NavigationRequestResult.Rejected("주차 상태가 확인된 뒤 경로를 요청할 수 있어요.")
        }
        return app.navigationGateway.requestRoute(request)
    }

    suspend fun requestLiveRouteConsent(): RouteConsentDecision = app.routePrivacyRepository.requestLiveConsent()

    suspend fun withdrawRouteConsent(): WithdrawalReceipt = app.routePrivacyRepository.withdraw()

    suspend fun requestPublishedDelete(confirmed: Boolean): PublishedDeleteDecision =
        latestDraft.value?.let { app.routePrivacyRepository.requestPublishedDelete(it.id, confirmed) }
            ?: PublishedDeleteDecision.NotPublished
}
