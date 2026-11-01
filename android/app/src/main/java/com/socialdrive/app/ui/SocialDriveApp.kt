package com.socialdrive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.DemoContentMode
import com.socialdrive.app.domain.DriveSessionState
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.safety.SafetyPolicy
import com.socialdrive.app.ui.screens.DetailScreen
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.PublishScreen
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.screens.RecordingStopDialog
import com.socialdrive.app.ui.screens.SettingsScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme

@Composable
fun SocialDriveApp(repository: FakeDriveRepository = remember { FakeDriveRepository() }) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var motion by rememberSaveable { mutableStateOf(MotionState.Parked) }
    var theme by rememberSaveable { mutableStateOf(ThemeMode.Night) }
    var selectedDrive by rememberSaveable { mutableStateOf("demo-coast") }
    var blockedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var highlights by rememberSaveable { mutableIntStateOf(0) }
    var demoContentMode by rememberSaveable { mutableStateOf(DemoContentMode.Content) }
    var sessionState by rememberSaveable { mutableStateOf(DriveSessionState.Idle) }
    var uploadState by rememberSaveable { mutableStateOf(UploadState.Draft) }
    var stopConfirmationRequested by rememberSaveable { mutableStateOf(false) }

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
        sessionState = DriveSessionState.DraftReady
        uploadState = UploadState.Draft
        if (motion == MotionState.Parked) {
            navigate(AppDestination.Publish)
        } else {
            blockedMessage = "기록을 초안으로 안전하게 보존했습니다. 주차 상태가 확인되면 게시할 수 있어요."
            destination = AppDestination.Home
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
                        demoMode = demoContentMode,
                        blockedMessage = blockedMessage,
                        onDetail = { selectedDrive = it; navigate(AppDestination.Detail) },
                        onRecord = { sessionState = DriveSessionState.Recording; navigate(AppDestination.Recording) },
                        onSettings = { navigate(AppDestination.Settings) },
                        onRetry = { demoContentMode = DemoContentMode.Content },
                        onDemoModeChange = { demoContentMode = it },
                    )
                    AppDestination.Detail -> DetailScreen(
                        drive = requireNotNull(repository.drive(selectedDrive)),
                        navigationCapability = CapabilityState.Unavailable,
                        onBack = { navigate(AppDestination.Home) },
                        onPublish = { navigate(AppDestination.Publish) },
                    )
                    AppDestination.Recording -> RecordingScreen(
                        snapshot = RecordingSnapshot(
                            elapsed = "01:24:17",
                            distance = "64.2 km",
                            highlights = highlights,
                            gps = CapabilityState.Available,
                            vehicle = CapabilityState.Unavailable,
                        ),
                        onHighlight = { highlights += 1 },
                        onRequestStop = { stopConfirmationRequested = true },
                    )
                    AppDestination.Publish -> PublishScreen(
                        canEdit = SafetyPolicy.canEdit(motion),
                        uploadState = uploadState,
                        onUploadStateChange = { uploadState = it },
                        onBack = { navigate(AppDestination.Detail) },
                        onDone = { sessionState = DriveSessionState.Idle; navigate(AppDestination.Home) },
                    )
                    AppDestination.Settings -> SettingsScreen(
                        motion = motion,
                        theme = theme,
                        onMotionChange = {
                            motion = it
                            if (it != MotionState.Parked) {
                                blockedMessage = "주행 상태로 전환되어 탐색과 설정을 잠갔어요."
                                destination = AppDestination.Home
                            }
                        },
                        onThemeChange = { theme = it },
                        onBack = { navigate(AppDestination.Home) },
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
