package com.socialdrive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.ConflictReviewUiState
import com.socialdrive.app.domain.ConflictUiPhase
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.ConflictReviewScreen
import com.socialdrive.app.ui.screens.PublishScreen
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme

private val previewRepository = FakeDriveRepository()

@Preview(name = "Night · feed content", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun NightFeedPreview() = SocialDriveTheme(ThemeMode.Night) {
    PreviewHome(ContentState.Content(previewRepository.drives()))
}

@Preview(name = "Day · offline cache", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun DayOfflinePreview() = SocialDriveTheme(ThemeMode.Day) {
    PreviewHome(ContentState.Offline(previewRepository.drives()))
}

@Preview(name = "Driving · restricted home", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun DrivingHomePreview() = SocialDriveTheme(ThemeMode.Night) {
    HomeScreen(
        state = ContentState.Content(previewRepository.drives()),
        motion = MotionState.Driving,
        blockedMessage = null,
        onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {},
    )
}

@Preview(name = "Recording · night", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun RecordingPreview() = SocialDriveTheme(ThemeMode.Night) {
    RecordingScreen(
        snapshot = RecordingSnapshot(
            storyId = "demo-coast",
            route = requireNotNull(previewRepository.drive("demo-coast")).route,
            elapsed = "01:24:17",
            distance = "데모 경로",
            highlights = 3,
            gps = CapabilityState.Available,
            vehicle = CapabilityState.Unavailable,
        ),
        onHighlight = {},
        onRequestStop = {},
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

@Preview(name = "Conflict · day · large text", widthDp = 800, heightDp = 720, fontScale = 1.5f, showBackground = true)
@Composable
private fun ConflictLargeTextPreview() = SocialDriveTheme(ThemeMode.Day) {
    ConflictReviewScreen(
        state = ConflictReviewUiState(
            ConflictUiPhase.SnapshotReady,
            latestVersion = 8,
            latestTitle = "서버에 저장된 새 드라이브 이야기",
            latestSummary = "로컬 초안은 그대로 두고 비교를 위한 공개 요약만 표시합니다.",
            replayCount = 1,
        ),
        motion = MotionState.Parked,
        onFetch = {}, onAdopt = {}, onKeepLocal = {}, onBack = {},
    )
}

@Preview(name = "Conflict · night · driving lock", widthDp = 800, heightDp = 480, showBackground = true)
@Composable
private fun ConflictDrivingPreview() = SocialDriveTheme(ThemeMode.Night) {
    ConflictReviewScreen(
        state = ConflictReviewUiState(ConflictUiPhase.Detected),
        motion = MotionState.Driving,
        onFetch = {}, onAdopt = {}, onKeepLocal = {}, onBack = {},
    )
}

@Preview(name = "Conflict · night · fetching", widthDp = 800, heightDp = 480, showBackground = true)
@Composable
private fun ConflictFetchingPreview() = SocialDriveTheme(ThemeMode.Night) {
    ConflictReviewScreen(
        state = ConflictReviewUiState(ConflictUiPhase.Fetching),
        motion = MotionState.Parked,
        onFetch = {}, onAdopt = {}, onKeepLocal = {}, onBack = {},
    )
}

@Composable
private fun PreviewHome(state: ContentState<List<com.socialdrive.app.domain.DriveSummary>>) {
    HomeScreen(
        state = state,
        motion = MotionState.Parked,
        blockedMessage = null,
        onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {},
    )
}
