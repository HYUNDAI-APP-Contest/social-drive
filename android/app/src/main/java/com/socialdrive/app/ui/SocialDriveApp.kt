package com.socialdrive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.ConflictReviewDecision
import com.socialdrive.app.domain.DemoContentMode
import com.socialdrive.app.domain.DriveSessionState
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.NavigationRequestResult
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.domain.PublishedDeleteDecision
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.safety.SafetyPolicy
import com.socialdrive.app.ui.screens.DetailScreen
import com.socialdrive.app.ui.screens.ConflictReviewScreen
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.PublishScreen
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.screens.RecordingStopDialog
import com.socialdrive.app.ui.screens.SettingsScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme
import kotlinx.coroutines.launch

@Composable
fun SocialDriveApp(
    repository: FakeDriveRepository = remember { FakeDriveRepository() },
    offlineViewModel: SocialDriveViewModel = viewModel(),
) {
    val scope = rememberCoroutineScope()
    val activeRecording by offlineViewModel.activeRecording.collectAsStateWithLifecycle()
    val latestDraft by offlineViewModel.latestDraft.collectAsStateWithLifecycle()
    val persistedUploadState by offlineViewModel.uploadState.collectAsStateWithLifecycle()
    val conflictReview by offlineViewModel.conflictReview.collectAsStateWithLifecycle()
    val vehicleMotion by offlineViewModel.vehicleMotion.collectAsStateWithLifecycle()
    val navigationState by offlineViewModel.navigationState.collectAsStateWithLifecycle()
    val navigationCapability by offlineViewModel.navigationCapability.collectAsStateWithLifecycle()
    val routePrivacy by offlineViewModel.routePrivacy.collectAsStateWithLifecycle()
    val deleteRequestState by offlineViewModel.deleteRequestState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    val motion = vehicleMotion.motion
    var theme by rememberSaveable { mutableStateOf(ThemeMode.Night) }
    var selectedDrive by rememberSaveable { mutableStateOf("demo-coast") }
    var blockedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var demoContentMode by rememberSaveable { mutableStateOf(DemoContentMode.Content) }
    var sessionState by rememberSaveable { mutableStateOf(DriveSessionState.Idle) }
    var demoUploadState by rememberSaveable { mutableStateOf<UploadState?>(null) }
    var stopConfirmationRequested by rememberSaveable { mutableStateOf(false) }
    var privacyMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(persistedUploadState) {
        if (persistedUploadState != UploadState.Draft) demoUploadState = null
    }
    LaunchedEffect(motion) {
        if (motion != MotionState.Parked && destination !in setOf(AppDestination.Home, AppDestination.Recording)) {
            blockedMessage = "주차 상태를 확인할 수 없어 안전 화면으로 돌아왔어요."
            destination = AppDestination.Home
        }
    }
    fun navigate(target: AppDestination) {
        val decision = SafetyPolicy.canNavigate(target, motion)
        if (decision.allowed) {
            blockedMessage = null
            destination = target
        } else {
            blockedMessage = decision.reason
            destination = AppDestination.Home
        }
    }

    fun completeRecording() {
        stopConfirmationRequested = false
        scope.launch {
            val draft = offlineViewModel.finishActiveRecording()
            if (draft == null) {
                blockedMessage = "기록 상태를 복구하고 있어요. 초안 화면에서 다시 확인해 주세요."
                destination = AppDestination.Home
                return@launch
            }
            sessionState = DriveSessionState.DraftReady
            demoUploadState = null
            if (motion == MotionState.Parked) {
                navigate(AppDestination.Publish)
            } else {
                blockedMessage = "기록을 초안으로 안전하게 보존했습니다. 주차 상태가 확인되면 게시할 수 있어요."
                destination = AppDestination.Home
            }
        }
    }

    BackHandler(destination != AppDestination.Home) {
        if (destination == AppDestination.Recording) stopConfirmationRequested = true
        else destination = AppDestination.Home
    }

    SocialDriveTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if (destination == AppDestination.Recording && stopConfirmationRequested) {
                    RecordingStopDialog(
                        motion = motion,
                        onDismiss = { stopConfirmationRequested = false },
                        onConfirm = ::completeRecording,
                    )
                }
                when (destination) {
                    AppDestination.Home -> HomeScreen(
                        state = demoContentState(demoContentMode, repository),
                        motion = motion,
                        vehicleStatus = vehicleMotion.integration,
                        demoMode = demoContentMode,
                        blockedMessage = blockedMessage,
                        onDetail = { selectedDrive = it; navigate(AppDestination.Detail) },
                        onRecord = {
                            sessionState = DriveSessionState.Recording
                            navigate(AppDestination.Recording)
                            scope.launch { offlineViewModel.startRecording() }
                        },
                        onSettings = { navigate(AppDestination.Settings) },
                        onRetry = { demoContentMode = DemoContentMode.Content },
                        onDemoModeChange = { demoContentMode = it },
                    )
                    AppDestination.Detail -> DetailScreen(
                        drive = requireNotNull(repository.drive(selectedDrive)),
                        navigationCapability = navigationCapability,
                        navigationState = navigationState,
                        onRequestNavigation = { request ->
                            scope.launch {
                                when (val result = offlineViewModel.requestNavigation(request)) {
                                    NavigationRequestResult.Accepted -> Unit
                                    NavigationRequestResult.Busy -> blockedMessage = "이전 경로 요청을 처리하고 있어요."
                                    is NavigationRequestResult.Rejected -> blockedMessage = result.userMessage
                                }
                            }
                        },
                        onBack = { navigate(AppDestination.Home) },
                        onPublish = { navigate(AppDestination.Publish) },
                    )
                    AppDestination.Recording -> RecordingScreen(
                        snapshot = RecordingSnapshot(
                            elapsed = "01:24:17",
                            distance = "64.2 km",
                            highlights = activeRecording?.highlightCount ?: 0,
                            gps = CapabilityState.Unavailable,
                            vehicle = vehicleMotion.integration.capability,
                            routeSource = routePrivacy.displayedSource,
                        ),
                        onHighlight = { scope.launch { offlineViewModel.addHighlight() } },
                        onRequestStop = { stopConfirmationRequested = true },
                    )
                    AppDestination.Publish -> PublishScreen(
                        canEdit = SafetyPolicy.canEdit(motion) && persistedUploadState == UploadState.Draft,
                        uploadState = demoUploadState ?: persistedUploadState,
                        onUploadStateChange = { requested ->
                            if (requested == UploadState.Uploading && latestDraft != null) {
                                demoUploadState = null
                                scope.launch {
                                    when (val result = offlineViewModel.enqueuePublish(motion)) {
                                        is EnqueueResult.Rejected -> blockedMessage = result.message
                                        EnqueueResult.MissingDraft -> blockedMessage = "게시할 초안을 찾지 못했어요. 기록은 삭제하지 않았습니다."
                                        EnqueueResult.NeedsConflictReview -> navigate(AppDestination.ConflictReview)
                                        else -> Unit
                                    }
                                }
                            } else {
                                demoUploadState = requested
                            }
                        },
                        onBack = { navigate(AppDestination.Detail) },
                        onDone = { sessionState = DriveSessionState.Idle; navigate(AppDestination.Home) },
                        initialTitle = latestDraft?.title ?: "빛나는 해안도로의 저녁",
                        initialDescription = latestDraft?.description ?: "바다를 따라 만난 좋은 순간을 나눠요.",
                        onCopyChanged = { title, description ->
                            scope.launch { offlineViewModel.updateDraftCopy(title, description) }
                        },
                        canAct = SafetyPolicy.canEdit(motion),
                        editingRestriction = if (motion == MotionState.Parked) {
                            "게시 대기열에 추가되어 원본을 안전하게 잠갔어요."
                        } else {
                            "주차 상태를 확인한 뒤 게시물을 편집할 수 있어요."
                        },
                        routeSource = routePrivacy.displayedSource,
                        onReviewConflict = { navigate(AppDestination.ConflictReview) },
                    )
                    AppDestination.ConflictReview -> ConflictReviewScreen(
                        state = conflictReview,
                        motion = motion,
                        onFetch = {
                            scope.launch {
                                when (offlineViewModel.fetchConflictLatest()) {
                                    ConflictReviewDecision.NotParked -> blockedMessage = "주차 상태가 다시 확인되지 않아 최신 버전을 요청하지 않았어요."
                                    ConflictReviewDecision.IdentityMismatch,
                                    ConflictReviewDecision.Missing -> blockedMessage = "충돌 정보를 안전하게 확인할 수 없어 로컬 초안을 유지합니다."
                                    else -> Unit
                                }
                            }
                        },
                        onAdopt = {
                            scope.launch {
                                when (offlineViewModel.adoptConflictLatest()) {
                                    ConflictReviewDecision.Applied -> navigate(AppDestination.Publish)
                                    ConflictReviewDecision.NotParked -> blockedMessage = "주차 상태가 다시 확인되지 않아 재게시를 시작하지 않았어요."
                                    else -> blockedMessage = "서버 버전을 적용하지 않았어요. 로컬 초안은 그대로 있습니다."
                                }
                            }
                        },
                        onKeepLocal = {
                            scope.launch {
                                if (offlineViewModel.keepConflictLocal() == ConflictReviewDecision.NotParked) {
                                    blockedMessage = "주차 상태가 다시 확인되지 않아 변경하지 않았어요."
                                }
                            }
                        },
                        onBack = { navigate(AppDestination.Publish) },
                    )
                    AppDestination.Settings -> SettingsScreen(
                        motion = motion,
                        theme = theme,
                        vehicleStatus = vehicleMotion.integration,
                        navigationStatus = navigationCapability,
                        onMotionChange = {
                            scope.launch {
                                if (offlineViewModel.requestDemoMotion(it)) {
                                    if (it != MotionState.Parked) {
                                        blockedMessage = "주행 상태로 전환되어 탐색과 설정을 잠갔어요."
                                        destination = AppDestination.Home
                                    }
                                } else {
                                    blockedMessage = "실차 상태는 앱에서 바꿀 수 없어요."
                                }
                            }
                        },
                        onThemeChange = { theme = it },
                        onBack = { navigate(AppDestination.Home) },
                        routePrivacy = routePrivacy,
                        privacyMessage = privacyMessage,
                        onRequestLiveConsent = {
                            scope.launch {
                                val decision = offlineViewModel.requestLiveRouteConsent()
                                privacyMessage = if (decision.unavailableReason != null) {
                                    "실시간 경로 SDK가 아직 제공되지 않아 동의를 저장하지 않았어요. 데모 데이터만 계속 사용합니다."
                                } else {
                                    "경로 동의 상태를 저장했어요."
                                }
                            }
                        },
                        onWithdrawConsent = {
                            scope.launch {
                                val result = offlineViewModel.withdrawRouteConsent()
                                privacyMessage = "동의를 철회하고 미게시 경로 ${result.purgedUnpublishedPointCount}개를 삭제했어요."
                            }
                        },
                        hasPublishedDraft = latestDraft?.state == LocalState.PUBLISHED,
                        deleteRequestState = deleteRequestState,
                        onConfirmPublishedDelete = {
                            scope.launch {
                                privacyMessage = when (offlineViewModel.requestPublishedDelete(confirmed = true)) {
                                    PublishedDeleteDecision.DeletePending -> "삭제 요청을 안전하게 저장했어요. 서버의 204 확인 전까지 로컬 원본을 유지합니다."
                                    PublishedDeleteDecision.ConfirmationRequired -> "삭제 확인이 필요해요."
                                    PublishedDeleteDecision.NotPublished -> "게시된 기록만 삭제 요청할 수 있어요."
                                    PublishedDeleteDecision.IdentityUnavailable -> "삭제할 게시 기록의 안전한 식별 정보를 확인할 수 없어요. 로컬 원본은 유지됩니다."
                                    is PublishedDeleteDecision.MotionRejected -> "주차 상태가 다시 확인되지 않아 삭제 대기를 저장하지 않았어요."
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun demoContentState(
    mode: DemoContentMode,
    repository: FakeDriveRepository,
): ContentState<List<com.socialdrive.app.domain.DriveSummary>> = when (mode) {
    DemoContentMode.Loading -> ContentState.Loading
    DemoContentMode.Content -> ContentState.Content(repository.drives())
    DemoContentMode.Empty -> ContentState.Empty
    DemoContentMode.Offline -> ContentState.Offline(repository.drives())
    DemoContentMode.Error -> ContentState.Error("피드를 불러오지 못했어요. 저장된 기록은 안전합니다.")
    DemoContentMode.PermissionDenied -> ContentState.PermissionDenied("위치")
    DemoContentMode.Retry -> ContentState.Retry("연결이 불안정합니다. 잠시 후 다시 시도해 주세요.")
}
