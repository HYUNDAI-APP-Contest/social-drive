package com.socialdrive.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.data.local.LocalState
import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.ConflictReviewDecision
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
import com.socialdrive.app.ui.screens.cloudPublicationBlockReason
import com.socialdrive.app.ui.components.memoryPaletteFor
import com.socialdrive.app.ui.theme.SocialDriveTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun SocialDriveApp(
    repository: FakeDriveRepository = remember { FakeDriveRepository() },
    offlineViewModel: SocialDriveViewModel = viewModel(),
) {
    val defaultStoryDrive = remember(repository) { requireNotNull(repository.drive("demo-coast")) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeRecording by offlineViewModel.activeRecording.collectAsStateWithLifecycle()
    val drafts by offlineViewModel.drafts.collectAsStateWithLifecycle()
    val selectedDraft by offlineViewModel.selectedDraft.collectAsStateWithLifecycle()
    val persistedUploadState by offlineViewModel.uploadState.collectAsStateWithLifecycle()
    val conflictReview by offlineViewModel.conflictReview.collectAsStateWithLifecycle()
    val vehicleMotion by offlineViewModel.vehicleMotion.collectAsStateWithLifecycle()
    val navigationState by offlineViewModel.navigationState.collectAsStateWithLifecycle()
    val navigationCapability by offlineViewModel.navigationCapability.collectAsStateWithLifecycle()
    val locationCapability by offlineViewModel.locationCapability.collectAsStateWithLifecycle()
    val textToSpeechCapability by offlineViewModel.textToSpeechCapability.collectAsStateWithLifecycle()
    val speechToTextCapability by offlineViewModel.speechToTextCapability.collectAsStateWithLifecycle()
    val speechRecognitionActive by offlineViewModel.speechRecognitionActive.collectAsStateWithLifecycle()
    val speechRecognitionState by offlineViewModel.speechRecognitionState.collectAsStateWithLifecycle()
    val cloudConnectionCapability by offlineViewModel.cloudConnectionCapability.collectAsStateWithLifecycle()
    val cloudAuthState by offlineViewModel.cloudAuthState.collectAsStateWithLifecycle()
    val routePrivacy by offlineViewModel.routePrivacy.collectAsStateWithLifecycle()
    val deleteRequestState by offlineViewModel.deleteRequestState.collectAsStateWithLifecycle()
    var recordingNowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    val motion = vehicleMotion.motion
    var theme by rememberSaveable { mutableStateOf(ThemeMode.Night) }
    var blockedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var memoryStory by rememberSaveable(stateSaver = MemoryStoryPresentation.Saver) {
        mutableStateOf(MemoryStoryPresentation.browsing(defaultStoryDrive))
    }
    var hydratedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var demoUploadState by rememberSaveable { mutableStateOf<UploadState?>(null) }
    var stopConfirmationRequested by rememberSaveable { mutableStateOf(false) }
    var privacyMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        offlineViewModel.refreshLocationPermission()
        if (granted) {
            scope.launch {
                val decision = offlineViewModel.requestLiveRouteConsent()
                privacyMessage = if (decision.shouldPersist) "실시간 경로 수집에 동의했습니다. 원본 좌표는 저장하지 않습니다."
                else "차량 위치 기능을 사용할 수 없습니다."
            }
        } else {
            privacyMessage = "위치 권한이 거부되어 실시간 경로를 수집하지 않습니다."
        }
    }
    val speechPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        offlineViewModel.refreshSpeechPermissions()
    }

    DisposableEffect(lifecycleOwner, offlineViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                offlineViewModel.refreshLocationPermission()
                offlineViewModel.refreshSpeechPermissions()
            } else if (event == Lifecycle.Event.ON_STOP) {
                offlineViewModel.cancelDictationIfActive()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(persistedUploadState) {
        if (persistedUploadState != UploadState.Draft) demoUploadState = null
    }
    LaunchedEffect(activeRecording?.id, activeRecording?.highlightCount) {
        activeRecording?.let { recording ->
            memoryStory = memoryStory.recordingProgress(recording.highlightCount)
        }
    }
    LaunchedEffect(activeRecording?.id) {
        val recovered = recoverRecordingDestination(destination, activeRecording != null)
        if (recovered != destination) {
            if (destination == AppDestination.Publish) offlineViewModel.cancelDictationIfActive()
            blockedMessage = null
            destination = recovered
        }
    }
    LaunchedEffect(activeRecording?.id) {
        if (activeRecording == null) return@LaunchedEffect
        while (true) {
            recordingNowEpochMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    LaunchedEffect(selectedDraft?.id, selectedDraft?.highlightCount, activeRecording?.id) {
        val draft = selectedDraft
        if (activeRecording == null && draft != null && hydratedDraftId != draft.id) {
            val fallback = repository.drive(draft.storyId) ?: defaultStoryDrive
            val localStory = draft.toLocalDriveSummary(fallback)
            memoryStory = MemoryStoryPresentation.browsing(localStory).finishDraft(draft.highlightCount)
            hydratedDraftId = draft.id
        }
    }
    LaunchedEffect(motion) {
        if (motion != MotionState.Parked && destination !in setOf(AppDestination.Home, AppDestination.Recording)) {
            if (destination == AppDestination.Publish) offlineViewModel.cancelDictationIfActive()
            blockedMessage = "주차 상태를 확인할 수 없어 안전 화면으로 돌아왔어요."
            destination = AppDestination.Home
        }
    }
    fun navigate(target: AppDestination) {
        val decision = SafetyPolicy.canNavigate(target, motion)
        if (decision.allowed) {
            if (destination == AppDestination.Publish && target != AppDestination.Publish) {
                offlineViewModel.cancelDictationIfActive()
            }
            if (target == AppDestination.Publish) offlineViewModel.resetDictationFeedback()
            blockedMessage = null
            destination = target
        } else {
            if (destination == AppDestination.Publish) offlineViewModel.cancelDictationIfActive()
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
            val fallback = repository.drive(draft.storyId) ?: defaultStoryDrive
            memoryStory = MemoryStoryPresentation.browsing(draft.toLocalDriveSummary(fallback))
                .finishDraft(draft.highlightCount)
            hydratedDraftId = draft.id
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
        else {
            if (destination == AppDestination.Publish) offlineViewModel.cancelDictationIfActive()
            destination = AppDestination.Home
        }
    }

    SocialDriveTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                val localDraftDrives = drafts.map { draft ->
                    draft.toLocalDriveSummary(repository.drive(draft.storyId) ?: defaultStoryDrive)
                }
                val localDraftDrive = localDraftDrives.firstOrNull { it.id == memoryStory.driveId }
                val homeDrives = localDraftDrives + repository.drives()
                val storyBase = localDraftDrive
                    ?: repository.drive(memoryStory.driveId)
                    ?: defaultStoryDrive
                val presentedStory = memoryStory.present(storyBase)
                val displayedRoute = presentedStory.route
                val displayedDraft = selectedDraft?.takeIf { "local-${it.id}" == presentedStory.id }
                val displayedUploadState = if (displayedDraft == null) {
                    demoUploadState ?: UploadState.Draft
                } else {
                    persistedUploadState
                }
                if (destination == AppDestination.Recording && stopConfirmationRequested) {
                    RecordingStopDialog(
                        motion = motion,
                        onDismiss = { stopConfirmationRequested = false },
                        onConfirm = ::completeRecording,
                    )
                }
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "screen_transition",
                ) { activeDestination ->
                when (activeDestination) {
                    AppDestination.Home -> HomeScreen(
                        state = ContentState.Content(homeDrives),
                        motion = motion,
                        vehicleStatus = vehicleMotion.integration,
                        blockedMessage = blockedMessage,
                        onDetail = { id ->
                            homeDrives.firstOrNull { it.id == id }?.let { drive ->
                                if (id.startsWith("local-")) {
                                    offlineViewModel.selectDraft(id.removePrefix("local-"))
                                }
                                memoryStory = memoryStory.select(drive)
                            }
                            navigate(AppDestination.Detail)
                        },
                        onRecord = {
                            memoryStory = memoryStory.beginRecording(defaultStoryDrive)
                            navigate(AppDestination.Recording)
                            scope.launch { offlineViewModel.startRecording() }
                        },
                        onSettings = { navigate(AppDestination.Settings) },
                        onRetry = {},
                    )
                    AppDestination.Detail -> DetailScreen(
                        drive = presentedStory,
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
                            storyId = presentedStory.id,
                            route = recordingArtworkRoute(routePrivacy.displayedSource, displayedRoute),
                            elapsed = formatRecordingElapsed(
                                activeRecording?.startedAtEpochMs ?: recordingNowEpochMs,
                                recordingNowEpochMs,
                            ),
                            distance = recordingRouteStatus(routePrivacy.displayedSource),
                            highlights = presentedStory.highlights,
                            gps = locationCapability.capability,
                            vehicle = vehicleMotion.integration.capability,
                            routeSource = routePrivacy.displayedSource,
                        ),
                        onHighlight = { scope.launch { offlineViewModel.addHighlight() } },
                        onRequestStop = { stopConfirmationRequested = true },
                    )
                    AppDestination.Publish -> {
                        PublishScreen(
                            canEdit = SafetyPolicy.canEdit(motion) && displayedUploadState == UploadState.Draft,
                            uploadState = displayedUploadState,
                            onUploadStateChange = { requested ->
                                if (requested == UploadState.Uploading && displayedDraft != null) {
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
                            onDone = { navigate(AppDestination.Home) },
                            initialTitle = displayedDraft?.title ?: presentedStory.title,
                            initialDescription = displayedDraft?.description ?: presentedStory.summary,
                            onCopyChanged = { title, description ->
                                if (displayedDraft != null) {
                                    scope.launch { offlineViewModel.updateDraftCopy(title, description) }
                                }
                            },
                            canAct = SafetyPolicy.canEdit(motion),
                            editingRestriction = if (motion == MotionState.Parked) {
                                "게시 대기열에 추가되어 원본을 안전하게 잠갔어요."
                            } else {
                                "주차 상태를 확인한 뒤 게시물을 편집할 수 있어요."
                            },
                            routeSource = presentedStory.routeSource,
                            onReviewConflict = { navigate(AppDestination.ConflictReview) },
                            route = localDraftDrive?.route ?: displayedRoute,
                            highlightCount = displayedDraft?.highlightCount ?: presentedStory.highlights,
                            speechRecognition = speechToTextCapability.capability,
                            speechRecognitionActive = speechRecognitionActive,
                            speechRecognitionState = speechRecognitionState,
                            onDictateTitle = {
                                displayedDraft != null && offlineViewModel.requestTitleDictation()
                            },
                            onDictateDescription = {
                                displayedDraft != null && offlineViewModel.requestDescriptionDictation()
                            },
                            onCancelDictation = offlineViewModel::cancelDictation,
                            publicationBlockReason = cloudPublicationBlockReason(
                                isLocalDraft = displayedDraft != null,
                                status = cloudConnectionCapability,
                            ),
                            memoryPalette = memoryPaletteFor(presentedStory.id),
                        )
                    }
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
                        locationStatus = locationCapability,
                        textToSpeechStatus = textToSpeechCapability,
                        speechToTextStatus = speechToTextCapability,
                        cloudConnectionStatus = cloudConnectionCapability,
                        cloudAuthState = cloudAuthState,
                        onCloudSignIn = offlineViewModel::signInToCloud,
                        onCloudConfirm = offlineViewModel::confirmCloudSignIn,
                        onCloudSignOut = offlineViewModel::signOutFromCloud,
                        onThemeChange = { theme = it },
                        onBack = { navigate(AppDestination.Home) },
                        routePrivacy = routePrivacy,
                        privacyMessage = privacyMessage,
                        onWithdrawConsent = {
                            scope.launch {
                                val result = offlineViewModel.withdrawRouteConsent()
                                privacyMessage = buildString {
                                    append("동의를 철회하고 미게시 경로 ${result.purgedUnpublishedPointCount}개를 삭제했어요.")
                                    if (result.remoteCleanupDraftIds.isNotEmpty()) {
                                        append(" 원격 초안 ${result.remoteCleanupDraftIds.size}개도 삭제 대기열에 추가했어요.")
                                    }
                                }
                            }
                        },
                        onEnableLiveRoute = {
                            val permission = offlineViewModel.locationPermission
                            if (permission != null) locationPermissionLauncher.launch(permission)
                            else privacyMessage = "이 실행 모드에서는 실제 위치를 수집하지 않습니다."
                        },
                        onRequestSpeechPermissions = {
                            val permissions = offlineViewModel.speechPermissions
                            if (permissions.isNotEmpty()) speechPermissionLauncher.launch(permissions.toTypedArray())
                        },
                        hasPublishedDraft = selectedDraft?.state == LocalState.PUBLISHED,
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
}
