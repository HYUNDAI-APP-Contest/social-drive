package com.socialdrive.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.socialdrive.app.domain.ConflictReviewUiState
import com.socialdrive.app.domain.ConflictUiPhase
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.ui.screens.ConflictReviewScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConflictReviewUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = PleosComposeHost()

    @Before fun launchHost() = host.launch()
    @After fun closeHost() = host.close()

    @Test fun parkedReadyShowsSafeCopyAndExplicitActions() {
        content(MotionState.Parked, ConflictReviewUiState(ConflictUiPhase.SnapshotReady, 8, "서버 제목", "서버 요약", 0))
        compose.onNodeWithText("서버의 새 버전이 있어요").assertIsDisplayed()
        compose.onNodeWithText("자동으로 덮어쓰지 않았습니다. 초안은 이 기기에 그대로 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("최신 버전을 기준으로 다시 게시").assertIsDisplayed()
        compose.onNodeWithText("로컬 초안 유지").assertIsDisplayed()
    }

    @Test fun drivingNeverExposesMutationControls() {
        assertMotionLock(MotionState.Driving)
    }

    @Test fun unknownNeverExposesMutationControls() {
        assertMotionLock(MotionState.Unknown)
    }

    @Test fun fetchingShowsImmediateProgressCopyAndNoDuplicateFetchAction() {
        content(MotionState.Parked, ConflictReviewUiState(ConflictUiPhase.Fetching))
        compose.onNodeWithText("최신 버전을 한 번 확인하고 있어요…").assertIsDisplayed()
        compose.onNodeWithText("최신 버전 확인").assertDoesNotExist()
    }

    private fun content(motion: MotionState, state: ConflictReviewUiState) {
        host.setContent {
            SocialDriveTheme(ThemeMode.Night) {
                ConflictReviewScreen(state, motion, {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
    }

    private fun assertMotionLock(motion: MotionState) {
        content(motion, ConflictReviewUiState(ConflictUiPhase.Detected))
        compose.onNodeWithText("최신 버전 확인").assertDoesNotExist()
        compose.onNodeWithText("로컬 초안 유지").assertDoesNotExist()
        compose.onNodeWithText("주차 상태를 확인한 뒤 충돌을 검토할 수 있어요.").assertIsDisplayed()
    }
}
