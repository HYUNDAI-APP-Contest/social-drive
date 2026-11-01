package com.socialdrive.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.data.fake.FakeDriveRepository
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.DemoContentMode
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSafetyUiTest {
    @get:Rule
    val compose = createComposeRule()

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
        compose.setContent {
            SocialDriveTheme(ThemeMode.Night) {
                HomeScreen(
                    state = ContentState.Content(drives),
                    motion = motion,
                    demoMode = DemoContentMode.Content,
                    blockedMessage = null,
                    onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {}, onDemoModeChange = {},
                )
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class NavigationSafetyUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackDuringRecordingShowsTheSameStopConfirmation() {
        compose.onNodeWithText("기록 시작", substring = true).performClick()
        pressBack()
        compose.onNodeWithText("기록을 종료할까요?").assertIsDisplayed()
        compose.onNodeWithText("초안으로 저장").assertIsDisplayed()
        compose.onNodeWithText("계속 기록").assertIsDisplayed()
    }

    @Test
    fun parkedUserCanReachPublishThroughDetailOnly() {
        compose.onNodeWithText("빛나는 해안도로의 저녁").performClick()
        compose.onNodeWithText("이 드라이브 게시하기").performClick()
        compose.onNodeWithText("드라이브 게시").assertIsDisplayed()
    }
}
