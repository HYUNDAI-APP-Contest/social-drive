package com.socialdrive.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.AppDestination
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.safety.SafetyPolicy
import com.socialdrive.app.ui.screens.DetailScreen
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.PublishScreen
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.screens.RecordingStopDialog
import com.socialdrive.app.ui.theme.SocialDriveTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSafetyUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val host = PleosComposeHost()

    @Before fun launchHost() = host.launch()
    @After fun closeHost() = host.close()

    @Test
    fun drivingHomeHidesFeedFiltersAndSettings() {
        showRestrictedHome(MotionState.Driving)
        compose.onNodeWithText("주행에 집중하세요").assertIsDisplayed()
        compose.onNodeWithText("전체").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정 열기").assertDoesNotExist()
        compose.onNodeWithText("빛나는 해안도로의 저녁").assertDoesNotExist()
    }

    @Test
    fun unknownMotionAlsoFailsClosed() {
        showRestrictedHome(MotionState.Unknown)
        compose.onNodeWithText("차량 상태를 확인하고 있어요").assertIsDisplayed()
        compose.onNodeWithText("전체").assertDoesNotExist()
        compose.onNodeWithContentDescription("설정 열기").assertDoesNotExist()
    }

    private fun showRestrictedHome(motion: MotionState) {
        val drives = FakeDriveRepository().drives()
        host.setContent {
            SocialDriveTheme(ThemeMode.Night) {
                HomeScreen(
                    state = ContentState.Content(drives),
                    motion = motion,
                    blockedMessage = null,
                    onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {},
                )
            }
        }
        compose.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
class NavigationSafetyUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val host = PleosComposeHost()

    @Before
    fun launchHost() {
        host.launch()
        host.setContent { NavigationContractContent() }
        compose.waitForIdle()
    }
    @After fun closeHost() = host.close()

    @Test
    fun activityBackDispatcherDuringRecordingShowsStopConfirmation() {
        compose.onNodeWithText("기록 시작", substring = true).performClick()
        compose.onNodeWithText("기록 중", substring = true).assertIsDisplayed()
        host.dispatchBackToActivity()
        compose.waitForIdle()
        compose.onNodeWithText("기록을 종료할까요?").assertIsDisplayed()
        compose.onNodeWithText("초안으로 저장").assertIsDisplayed()
        compose.onNodeWithText("계속 기록").assertIsDisplayed()
    }

    @Test
    fun parkedUserCanReachPublishThroughDetailOnly() {
        compose.onNodeWithText("빛나는 해안도로의 저녁").performClick()
        compose.onNodeWithText("빛나는 해안도로의 저녁").assertIsDisplayed()
        compose.onNodeWithText("이 드라이브 게시하기").assertIsDisplayed()
        compose.onNodeWithText("이 드라이브 게시하기").performClick()
        compose.onNodeWithText("드라이브 게시").assertIsDisplayed()
    }

    @Test
    fun unavailableNavigationWithRequestShowsGuidanceInsteadOfSuccessAction() {
        val repository = FakeDriveRepository()
        val unavailable = CapabilityStatus(
            capability = CapabilityState.Unavailable,
            source = IntegrationSource.Unavailable,
            label = "사용 불가",
        )
        val driveWithRequest = requireNotNull(repository.drive("demo-coast")).copy(
            navigationRequest = NavigationRequest(
                requestId = "privacy-reviewed-route",
                destination = NavigationPlace("테스트 목적지", 37.0, 127.0),
            ),
        )

        host.setContent {
            SocialDriveTheme(ThemeMode.Day) {
                DetailScreen(
                    drive = driveWithRequest,
                    navigationCapability = unavailable,
                    navigationState = NavigationState.Idle(unavailable),
                    onRequestNavigation = {},
                    onBack = {},
                    onPublish = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("차량 내비게이션 연결 안내", substring = true).assertIsDisplayed()
        compose.onNodeWithText("차량 내비게이션으로 보내기", substring = true).assertDoesNotExist()
    }
}

/**
 * Deterministic navigation contract for androidTest. It intentionally owns no app database,
 * WorkManager, ViewModel, cloud, or PLEOS integration state; production screens and safety wiring
 * remain under test while integration availability is explicit and inert.
 */
@Composable
private fun NavigationContractContent() {
    val motion = MotionState.Parked
    val repository = FakeDriveRepository()
    val navigationCapability = CapabilityStatus(
        capability = CapabilityState.Unavailable,
        source = IntegrationSource.Demo,
        label = "테스트 내비게이션",
    )
    val navigationState = NavigationState.Idle(navigationCapability)
    var destination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    var selectedDrive by rememberSaveable { mutableStateOf("demo-coast") }
    var stopConfirmationRequested by rememberSaveable { mutableStateOf(false) }

    fun navigate(target: AppDestination) {
        val decision = SafetyPolicy.canNavigate(target, motion)
        destination = if (decision.allowed) target else AppDestination.Home
    }

    BackHandler(destination != AppDestination.Home) {
        if (destination == AppDestination.Recording) stopConfirmationRequested = true
        else destination = AppDestination.Home
    }

    SocialDriveTheme(ThemeMode.Night) {
        if (destination == AppDestination.Recording && stopConfirmationRequested) {
            RecordingStopDialog(
                motion = motion,
                onDismiss = { stopConfirmationRequested = false },
                onConfirm = {
                    stopConfirmationRequested = false
                    navigate(AppDestination.Publish)
                },
            )
        }
        when (destination) {
            AppDestination.Home -> HomeScreen(
                state = ContentState.Content(repository.drives()),
                motion = motion,
                blockedMessage = null,
                onDetail = { selectedDrive = it; navigate(AppDestination.Detail) },
                onRecord = { navigate(AppDestination.Recording) },
                onSettings = {},
                onRetry = {},
            )
            AppDestination.Detail -> DetailScreen(
                drive = requireNotNull(repository.drive(selectedDrive)),
                navigationCapability = navigationCapability,
                navigationState = navigationState,
                onRequestNavigation = {},
                onBack = { navigate(AppDestination.Home) },
                onPublish = { navigate(AppDestination.Publish) },
            )
            AppDestination.Recording -> RecordingScreen(
                snapshot = RecordingSnapshot(
                    elapsed = "00:01:24",
                    distance = "1.2 km",
                    highlights = 1,
                    gps = CapabilityState.Unavailable,
                    vehicle = CapabilityState.Unavailable,
                    routeSource = RouteSource.DemoPreset,
                ),
                onHighlight = {},
                onRequestStop = { stopConfirmationRequested = true },
            )
            AppDestination.Publish -> PublishScreen(
                canEdit = true,
                uploadState = UploadState.Draft,
                onUploadStateChange = {},
                onBack = {},
                onDone = {},
                routeSource = RouteSource.DemoPreset,
            )
            AppDestination.ConflictReview,
            AppDestination.Settings,
            -> Unit
        }
    }
}
