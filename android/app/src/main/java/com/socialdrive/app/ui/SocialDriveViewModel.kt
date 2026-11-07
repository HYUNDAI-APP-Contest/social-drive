package com.socialdrive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.socialdrive.app.SocialDriveApplication
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.local.RecordingEntity
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.ConflictReviewDecision
import com.socialdrive.app.domain.ConflictReviewUiState
import com.socialdrive.app.domain.ConflictUiPhase
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
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.domain.VehicleMotionStatus
import com.socialdrive.app.domain.SpeechRecognitionPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal fun selectVisibleDraft(items: List<DraftSnapshot>, selectedId: String?): DraftSnapshot? =
    items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()

internal data class DraftCopyUpdate(val draftId: String, val title: String, val description: String)

internal class ConflatedDraftCopyWriter(
    scope: CoroutineScope,
    persist: suspend (DraftCopyUpdate) -> Unit,
) {
    private val lock = Any()
    private val pendingByDraftId = linkedMapOf<String, DraftCopyUpdate>()
    private val idleWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private var persisting = false
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wakeups) {
                synchronized(lock) { persisting = true }
                while (true) {
                    val update = synchronized(lock) {
                        pendingByDraftId.entries.firstOrNull()?.let { entry ->
                            pendingByDraftId.remove(entry.key)
                        }
                    } ?: break
                    try {
                        persist(update)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Keep the writer alive so a later user edit can still be persisted.
                    }
                }
                val completedWaiters = synchronized(lock) {
                    persisting = false
                    if (pendingByDraftId.isEmpty()) idleWaiters.toList().also { idleWaiters.clear() }
                    else emptyList()
                }
                completedWaiters.forEach { it.complete(Unit) }
            }
        }
    }

    fun submit(update: DraftCopyUpdate): Boolean {
        synchronized(lock) { pendingByDraftId[update.draftId] = update }
        return wakeups.trySend(Unit).isSuccess
    }

    suspend fun awaitIdle() {
        val waiter = synchronized(lock) {
            if (pendingByDraftId.isEmpty() && !persisting) null
            else CompletableDeferred<Unit>().also(idleWaiters::add)
        } ?: return
        try {
            waiter.await()
        } finally {
            synchronized(lock) { idleWaiters.remove(waiter) }
        }
    }
}

class SocialDriveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SocialDriveApplication
    private val draftCopyWriter = ConflatedDraftCopyWriter(viewModelScope) { update ->
        app.draftRepository.updateCopy(update.draftId, update.title, update.description)
    }

    val activeRecording: StateFlow<RecordingEntity?> = app.recordingRepository.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val drafts: StateFlow<List<DraftSnapshot>> = app.draftRepository.drafts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val driveFeed = app.driveFeedRepository.state

    fun refreshDriveFeed() = viewModelScope.launch { app.driveFeedRepository.refresh() }

    suspend fun loadDriveDetail(id: String) = app.driveFeedRepository.detail(id)

    private val selectedDraftId = MutableStateFlow<String?>(null)
    val selectedDraft: StateFlow<DraftSnapshot?> = combine(drafts, selectedDraftId) { items, selectedId ->
        selectVisibleDraft(items, selectedId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uploadState: StateFlow<UploadState> = selectedDraft
        .flatMapLatest { draft -> draft?.let { app.publishQueueRepository.observeState(it.id) } ?: flowOf(UploadState.Draft) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadState.Draft)

    @OptIn(ExperimentalCoroutinesApi::class)
    val conflictReview: StateFlow<ConflictReviewUiState> = selectedDraft
        .flatMapLatest { draft ->
            draft?.let { app.conflictReviewRepository.observe(it.id) }
                ?: flowOf(ConflictReviewUiState(ConflictUiPhase.Hidden))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ConflictReviewUiState(ConflictUiPhase.Hidden),
        )

    val vehicleMotion: StateFlow<VehicleMotionStatus> = app.vehicleMotionSource.status
    val navigationState: StateFlow<NavigationState> = app.navigationGateway.state
    val navigationCapability = app.navigationGateway.capability
    val locationCapability = app.auxiliaryCapabilitySource.location
    val textToSpeechCapability = app.auxiliaryCapabilitySource.textToSpeech
    val speechToTextCapability = app.auxiliaryCapabilitySource.speechToText
    val speechRecognitionActive = app.auxiliaryCapabilitySource.speechRecognitionActive
    val speechRecognitionState = app.auxiliaryCapabilitySource.speechRecognitionState
    val cloudConnectionCapability = app.cloudConnectionStatusSource.status
    val cloudAuthState = app.cloudAuth.state
    val locationPermission: String? get() = app.auxiliaryCapabilitySource.locationPermission
    val speechPermissions: List<String> get() = app.auxiliaryCapabilitySource.speechPermissions

    fun refreshLocationPermission() = app.auxiliaryCapabilitySource.refreshLocationPermission()
    fun refreshSpeechPermissions() = app.auxiliaryCapabilitySource.refreshSpeechPermissions()

    fun signInToCloud(username: String, password: CharArray) = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            app.cloudAuth.signIn(username, password)
        } finally {
            password.fill('\u0000')
        }
    }

    fun confirmCloudSignIn(response: CharArray) = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            app.cloudAuth.confirmSignIn(response)
        } finally {
            response.fill('\u0000')
        }
    }

    fun signOutFromCloud() = viewModelScope.launch { app.cloudAuth.signOut() }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val deleteRequestState: StateFlow<DeleteRequestState?> = selectedDraft
        .flatMapLatest { draft ->
            draft?.let {
                app.database.publishedDeleteDao().observe(it.id).map { request ->
                    request?.state?.let { state ->
                        runCatching { DeleteRequestState.valueOf(state) }.getOrDefault(DeleteRequestState.LEGACY_UNREVIEWABLE)
                    }
                }
            } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun startRecording() = app.recordingRepository.start().also {
        app.auxiliaryCapabilitySource.speakFeedback("드라이브 기록을 시작합니다")
    }

    suspend fun addHighlight() {
        activeRecording.value?.let {
            app.recordingRepository.addHighlight(it.id)
            app.auxiliaryCapabilitySource.speakFeedback("하이라이트를 저장했습니다")
        }
    }

    suspend fun finishActiveRecording(): DraftSnapshot? = app.recordingRepository.finishActiveAsDraft().also { draft ->
        if (draft != null) {
            selectedDraftId.value = draft.id
            app.auxiliaryCapabilitySource.speakFeedback("드라이브 기록을 완료했습니다")
        }
    }

    fun selectDraft(id: String): Boolean {
        if (drafts.value.none { it.id == id }) return false
        selectedDraftId.value = id
        return true
    }

    fun updateDraftCopy(title: String, description: String): Boolean =
        selectedDraft.value?.let { draft ->
            draftCopyWriter.submit(DraftCopyUpdate(draft.id, title, description))
        } ?: false

    fun requestTitleDictation(): Boolean = requestDictation { draft, text ->
        draftCopyWriter.submit(DraftCopyUpdate(draft.id, text, draft.description))
    }

    fun requestDescriptionDictation(): Boolean = requestDictation { draft, text ->
        draftCopyWriter.submit(DraftCopyUpdate(draft.id, draft.title, text))
    }

    fun cancelDictation() = app.auxiliaryCapabilitySource.stopSpeechRecognition()

    fun cancelDictationIfActive() {
        if (speechRecognitionActive.value) app.auxiliaryCapabilitySource.stopSpeechRecognition()
    }

    fun resetDictationFeedback() = app.auxiliaryCapabilitySource.resetSpeechRecognitionState()

    private fun requestDictation(update: (DraftSnapshot, String) -> Unit): Boolean {
        if (!SpeechRecognitionPolicy.canStart(vehicleMotion.value.motion, speechToTextCapability.value)) return false
        val draft = selectedDraft.value ?: return false
        return app.auxiliaryCapabilitySource.startSpeechRecognition { text, isFinal ->
            if (SpeechRecognitionPolicy.canCommit(vehicleMotion.value.motion, text, isFinal)) {
                update(draft, text.trim())
            }
        }
    }

    suspend fun enqueuePublish(motionState: MotionState): EnqueueResult {
        draftCopyWriter.awaitIdle()
        return selectedDraft.value?.let { app.publishScheduler.enqueue(it.id, motionState) } ?: EnqueueResult.MissingDraft
    }

    suspend fun fetchConflictLatest(): ConflictReviewDecision =
        selectedDraft.value?.let { app.conflictReviewRepository.fetchLatest(it.id) } ?: ConflictReviewDecision.Missing

    suspend fun keepConflictLocal(): ConflictReviewDecision =
        selectedDraft.value?.let { app.conflictReviewRepository.keepLocal(it.id) } ?: ConflictReviewDecision.Missing

    suspend fun adoptConflictLatest(): ConflictReviewDecision =
        selectedDraft.value?.let { app.conflictReviewRepository.adoptLatestAndReplay(it.id) } ?: ConflictReviewDecision.Missing

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
        selectedDraft.value?.let { app.routePrivacyRepository.requestPublishedDelete(it.id, confirmed) }
            ?: PublishedDeleteDecision.NotPublished
}
