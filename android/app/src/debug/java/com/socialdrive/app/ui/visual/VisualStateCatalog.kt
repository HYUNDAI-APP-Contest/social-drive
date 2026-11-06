package com.socialdrive.app.ui.visual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.ui.screens.DetailScreen
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.screens.SettingsScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme

const val VISUAL_FIXTURE_TAG = "visual_fixture"
const val JUDGE_VIEWPORT_WIDTH_PX = 1280
const val JUDGE_VIEWPORT_HEIGHT_PX = 720

enum class VisualScenario(val fixtureId: String) {
    HomeContent("home_content"),
    RecordingActive("recording_active"),
    ConsentLiveUnavailable("consent_live_unavailable"),
    MemoryDetailDemo("memory_detail_demo"),
    HomeLoading("home_loading"),
    HomeEmpty("home_empty"),
    HomeOfflineCached("home_offline_cached"),
    HomeError("home_error"),
}

private val fixtureRepository = FakeDriveRepository()
private val demoVehicle = CapabilityStatus(CapabilityState.Available, IntegrationSource.Demo, "차량 상태")
private val unavailableNavigation = CapabilityStatus(CapabilityState.Unavailable, IntegrationSource.Unavailable, "내비게이션")

/** Fixed visual input: no database, clock, network, WorkManager, Vehicle, or Navi callback. */
@Composable
fun VisualScenarioFixture(
    scenario: VisualScenario,
    theme: ThemeMode,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    SocialDriveTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier.testTag(VISUAL_FIXTURE_TAG)) {
                when (scenario) {
                    VisualScenario.HomeContent -> FixtureHome(ContentState.Content(fixtureRepository.drives()))
                    VisualScenario.HomeLoading -> FixtureHome(ContentState.Loading)
                    VisualScenario.HomeEmpty -> FixtureHome(ContentState.Empty)
                    VisualScenario.HomeOfflineCached -> FixtureHome(ContentState.Offline(fixtureRepository.drives()))
                    VisualScenario.HomeError -> FixtureHome(ContentState.Error("고정 테스트 오류"))
                    VisualScenario.RecordingActive -> RecordingScreen(
                        snapshot = RecordingSnapshot(
                            elapsed = "01:24:17",
                            distance = "64.2 km",
                            highlights = 3,
                            gps = CapabilityState.Unavailable,
                            vehicle = CapabilityState.Unavailable,
                            routeSource = RouteSource.DemoPreset,
                        ),
                        onHighlight = {},
                        onRequestStop = {},
                    )
                    VisualScenario.ConsentLiveUnavailable -> SettingsScreen(
                        motion = MotionState.Parked,
                        theme = theme,
                        vehicleStatus = demoVehicle,
                        navigationStatus = unavailableNavigation,
                        onThemeChange = {},
                        onBack = {},
                    )
                    VisualScenario.MemoryDetailDemo -> DetailScreen(
                        drive = requireNotNull(fixtureRepository.drive("demo-coast")),
                        navigationCapability = unavailableNavigation,
                        navigationState = NavigationState.Idle(unavailableNavigation),
                        onRequestNavigation = {},
                        onBack = {},
                        onPublish = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun FixtureHome(state: ContentState<List<com.socialdrive.app.domain.DriveSummary>>) {
    HomeScreen(
        state = state,
        motion = MotionState.Parked,
        vehicleStatus = demoVehicle,
        blockedMessage = null,
        onDetail = {},
        onRecord = {},
        onSettings = {},
        onRetry = {},
    )
}
