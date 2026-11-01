package com.socialdrive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.DemoContentMode
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.PublishScreen
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme

private val previewRepository = FakeDriveRepository()

@Preview(name = "Night · feed content", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun NightFeedPreview() = SocialDriveTheme(ThemeMode.Night) {
    PreviewHome(ContentState.Content(previewRepository.drives()), DemoContentMode.Content)
}

@Preview(name = "Day · offline cache", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun DayOfflinePreview() = SocialDriveTheme(ThemeMode.Day) {
    PreviewHome(ContentState.Offline(previewRepository.drives()), DemoContentMode.Offline)
}

@Preview(name = "Driving · restricted home", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun DrivingHomePreview() = SocialDriveTheme(ThemeMode.Night) {
    HomeScreen(
        state = ContentState.Content(previewRepository.drives()),
        motion = MotionState.Driving,
        demoMode = DemoContentMode.Content,
        blockedMessage = null,
        onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {}, onDemoModeChange = {}, onDemoParked = {},
    )
}

@Preview(name = "Recording · night", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun RecordingPreview() = SocialDriveTheme(ThemeMode.Night) {
    RecordingScreen(
        snapshot = RecordingSnapshot("01:24:17", "64.2 km", 3, CapabilityState.Available, CapabilityState.Unavailable),
        motion = MotionState.Driving,
        onHighlight = {},
        onStop = {},
    )
}

@Preview(name = "Publish · failed", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun PublishFailurePreview() = SocialDriveTheme(ThemeMode.Day) {
    PublishScreen(
        canEdit = true,
        uploadState = UploadState.Failed,
        onUploadStateChange = {},
        onBack = {},
        onDone = {},
    )
}

@Composable
private fun PreviewHome(state: ContentState<List<com.socialdrive.app.domain.DriveSummary>>, mode: DemoContentMode) {
    HomeScreen(
        state = state,
        motion = MotionState.Parked,
        demoMode = mode,
        blockedMessage = null,
        onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {}, onDemoModeChange = {}, onDemoParked = {},
    )
}
