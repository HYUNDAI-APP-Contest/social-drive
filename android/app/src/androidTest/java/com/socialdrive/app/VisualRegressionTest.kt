package com.socialdrive.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.SettingsScreen
import com.socialdrive.app.ui.theme.SocialDriveTheme
import com.socialdrive.app.ui.visual.JUDGE_VIEWPORT_HEIGHT_PX
import com.socialdrive.app.ui.visual.JUDGE_VIEWPORT_WIDTH_PX
import com.socialdrive.app.ui.visual.VISUAL_FIXTURE_TAG
import com.socialdrive.app.ui.visual.VisualScenario
import com.socialdrive.app.ui.visual.VisualScenarioFixture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun representativeStatesExposeKoreanStatusAndActions() {
        assertScenario(VisualScenario.HomeContent, "오늘의 드라이브")
        assertScenario(VisualScenario.RecordingActive, "데모 경로를 기록하고 있어요")
        assertScenario(VisualScenario.ConsentLiveUnavailable, "PLEOS 위치 권한 필요")
        compose.onNodeWithText("실시간 경로 사용 확인").assertDoesNotExist()
        assertScenario(VisualScenario.MemoryDetailDemo, "개인 위치를 제거한 비정밀 경로 아트워크")
        assertScenario(VisualScenario.HomeLoading, "드라이브를 불러오고 있어요")
        assertScenario(VisualScenario.HomeEmpty, "아직 남긴 드라이브가 없어요")
        compose.onNodeWithText("첫 드라이브 기록하기").assertIsDisplayed()
        assertScenario(VisualScenario.HomeOfflineCached, "오프라인 · 저장된 기록 표시 중")
        assertScenario(VisualScenario.HomeError, "드라이브를 불러오지 못했어요")
        compose.onNodeWithText("기기에 저장된 초안과 기록은 삭제되지 않았습니다.").assertIsDisplayed()
        compose.onNodeWithText("다시 시도").assertIsDisplayed()
        assertScenario(VisualScenario.PublishDraft, "좋았던 순간을 한 장의 이야기로")
    }

    @Test
    fun homeCategoryFiltersUpdateTheVisibleDriveCollection() {
        compose.onNodeWithText("오늘의 드라이브").assertIsDisplayed()
        compose.onNodeWithText("산").performClick()
        compose.onNodeWithText("가을, 단풍이 머문 길").assertIsDisplayed()
        compose.onNodeWithText("빛나는 해안도로의 저녁").assertDoesNotExist()
        compose.onNodeWithText("도심 속 특별한 저녁").assertDoesNotExist()
    }

    @Test
    fun runtimeMemoryFlowOpensDetailAndPublish() {
        compose.onNodeWithText("빛나는 해안도로의 저녁").performClick()
        compose.onNodeWithText("개인 위치를 제거한 비정밀 경로 아트워크").assertIsDisplayed()
        compose.onNodeWithText("이 드라이브 게시하기").performClick()
        compose.onNodeWithText("드라이브 게시").assertIsDisplayed()
        compose.onNodeWithText("좋았던 순간을 한 장의 이야기로").assertIsDisplayed()
    }

    @Test
    fun runtimeRecordingFlowOpensDistractionMinimizedScreen() {
        compose.onNodeWithText("기록 시작", substring = true).performClick()
        compose.onNodeWithText("데모 경로를 기록하고 있어요").assertIsDisplayed()
        compose.onNodeWithText("방금 좋았어", substring = true).assertIsDisplayed()
        compose.onNodeWithText("기록 종료", substring = true).assertIsDisplayed()
    }

    @Test
    fun offlineLargeTextKeepsHomeStructureWithoutDeadTagCopy() {
        showLargeText(VisualScenario.HomeOfflineCached)
        compose.onNodeWithText("오늘의 드라이브").assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithText("기록 시작", substring = true).assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithText("데모 차량 상태", substring = true).assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithContentDescription("설정 열기").assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithText("카드 태그", substring = true).assertDoesNotExist()
        compose.onNodeWithText("오프라인 · 저장된 기록 표시 중").assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithText("연결되면 최신 목록을 다시 확인합니다.").assertIsDisplayed().assertWithinFixture()
        assertJudgeViewport()
    }

    @Test
    fun everyRepresentativeActionMeets48DpAndAutomotivePrimaryActionsAreLarger() {
        VisualScenario.entries.forEach { scenario ->
            show(scenario)
            assertEveryEnabledActionIsAtLeast48Dp(scenario.fixtureId)
        }

        show(VisualScenario.HomeContent)
        compose.onNodeWithText("기록 시작", substring = true).assertHeightIsAtLeast(64.dp)
        show(VisualScenario.RecordingActive)
        compose.onNodeWithText("방금 좋았어", substring = true).assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("기록 종료", substring = true).assertHeightIsAtLeast(72.dp)
        show(VisualScenario.MemoryDetailDemo)
        compose.onNodeWithText("차량 내비게이션 연결 안내", substring = true).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("이 드라이브 게시하기").assertHeightIsAtLeast(48.dp)

        showSettingsWithAllActions()
        listOf("시스템", "주간", "야간", "동의 철회", "게시 기록 삭제 요청").forEach { label ->
            compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
        }
        assertEveryEnabledActionIsAtLeast48Dp("settings_all_actions")
    }

    @Test
    fun unavailableLiveRouteDoesNotContradictPersistedConsent() {
        showSettingsWithAllActions()
        compose.onNodeWithText("현재 수집하지 않음 · 기존 동의 저장됨").assertIsDisplayed()
        compose.onNodeWithText("현재 수집하지 않음 · 동의도 저장하지 않음").assertDoesNotExist()
        compose.onNodeWithText("동의 철회").assertIsDisplayed()

        show(VisualScenario.ConsentLiveUnavailable)
        compose.onNodeWithText("현재 수집하지 않음 · 동의도 저장하지 않음").assertIsDisplayed()
        compose.onNodeWithText("기존 동의 저장됨", substring = true).assertDoesNotExist()
        compose.onNodeWithText("동의 철회").assertDoesNotExist()
    }

    @Test
    fun offlineWithoutCacheShowsConnectionRecoveryState() {
        setJudgeContent {
            HomeScreen(
                state = ContentState.Offline(emptyList()),
                motion = MotionState.Parked,
                vehicleStatus = demoVehicle,
                blockedMessage = null,
                onDetail = {}, onRecord = {}, onSettings = {}, onRetry = {},
            )
        }
        compose.onNodeWithText("연결이 필요해요").assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithText("기기에 저장된 드라이브가 없습니다.").assertIsDisplayed().assertWithinFixture()
        compose.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).assertWithinFixture()
        assertEveryEnabledActionIsAtLeast48Dp("offline_empty")
    }

    @Test
    fun allRepresentativeStatesKeepPrimaryCopyInside1280x720AtLargeText() {
        val expectations = listOf(
            Triple(VisualScenario.HomeContent, "오늘의 드라이브", "기록 시작"),
            Triple(VisualScenario.RecordingActive, "데모 경로를 기록하고 있어요", "기록 종료"),
            Triple(VisualScenario.ConsentLiveUnavailable, "경로 데이터 및 동의", "PLEOS 위치 권한 필요"),
            Triple(VisualScenario.MemoryDetailDemo, "빛나는 해안도로의 저녁", "차량 내비게이션 연결 안내"),
            Triple(VisualScenario.HomeLoading, "드라이브를 불러오고 있어요", "저장된 기록을 확인하는 중입니다."),
            Triple(VisualScenario.HomeEmpty, "아직 남긴 드라이브가 없어요", "첫 드라이브 기록하기"),
            Triple(VisualScenario.HomeOfflineCached, "오프라인 · 저장된 기록 표시 중", "연결되면 최신 목록을 다시 확인합니다."),
            Triple(VisualScenario.HomeError, "드라이브를 불러오지 못했어요", "다시 시도"),
            Triple(VisualScenario.PublishDraft, "드라이브 게시", "좋았던 순간을 한 장의 이야기로"),
        )

        expectations.forEach { (scenario, primary, secondary) ->
            showLargeText(scenario)
            val primaryNode = compose.onNodeWithText(primary, substring = scenario == VisualScenario.RecordingActive)
            val secondaryNode = compose.onNodeWithText(secondary, substring = true)
            primaryNode.assertWithinFixture()
            secondaryNode.assertWithinFixture()
            assertFalse("$scenario primary copy overlaps its action/detail", primaryNode.bounds().overlaps(secondaryNode.bounds()))
        }
    }

    @Test
    fun recordingMomentShowsImmediateMemoryFeedback() {
        show(VisualScenario.RecordingActive)
        compose.onNodeWithText("방금 좋았어", substring = true).performClick()
        compose.onNodeWithText("순간 4 저장됨 · 주차 후 이야기에서 확인").assertIsDisplayed()
    }

    @Test
    fun detailMomentSelectionUpdatesTheStoryArtwork() {
        show(VisualScenario.MemoryDetailDemo)
        compose.onNodeWithContentDescription("등대가 보이는 길 순간 보기").performClick()
        compose.onNodeWithText("순간 2 · 18:03").assertIsDisplayed()
    }

    @Test
    fun consentStateKeepsJudgeCriticalIntegrationAndDataBoundaryVisibleAtLargeText() {
        showLargeText(VisualScenario.ConsentLiveUnavailable)
        listOf(
            "경로 데이터 및 동의",
            "현재 데모 경로만 사용 · 실시간 위치 미사용",
            "연동 및 보존 상태",
            "차량 상태",
            "차량 내비게이션",
            "차량 제어",
            "오프라인 초안",
            "기기에 보존",
            "데모 데이터 · 외부 연동 없음",
            "AWS 키, 사용자 토큰, 실제 위치는 포함되지 않습니다.",
        ).forEach { text ->
            compose.onNodeWithText(text).assertIsDisplayed().assertWithinFixture()
        }
        compose.onNodeWithText("0개", substring = true).assertDoesNotExist()
    }

    private fun assertScenario(scenario: VisualScenario, text: String) {
        show(scenario)
        compose.onNodeWithText(text, substring = false).assertIsDisplayed()
    }

    private fun show(scenario: VisualScenario, theme: ThemeMode = ThemeMode.Day) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                VisualScenarioFixture(scenario, theme, judgeViewportModifier())
            }
        }
        compose.waitForIdle()
        assertJudgeViewport()
    }

    private fun showLargeText(scenario: VisualScenario) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.3f)) {
                VisualScenarioFixture(scenario, ThemeMode.Day, judgeViewportModifier())
            }
        }
        compose.waitForIdle()
        assertJudgeViewport()
    }

    private fun showSettingsWithAllActions() {
        setJudgeContent {
            SettingsScreen(
                motion = MotionState.Parked,
                theme = ThemeMode.Day,
                vehicleStatus = demoVehicle,
                navigationStatus = unavailableNavigation,
                onThemeChange = {},
                onBack = {},
                routePrivacy = RoutePrivacyStatus(
                    RouteSource.DemoPreset,
                    RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON),
                    RouteConsentState.Granted,
                ),
                onWithdrawConsent = {},
                hasPublishedDraft = true,
                onConfirmPublishedDelete = {},
            )
        }
    }

    private fun setJudgeContent(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                SocialDriveTheme(ThemeMode.Day) {
                    Box(judgeViewportModifier().testTag(VISUAL_FIXTURE_TAG)) { content() }
                }
            }
        }
        compose.waitForIdle()
        assertJudgeViewport()
    }

    private fun judgeViewportModifier() = Modifier.requiredSize(
        JUDGE_VIEWPORT_WIDTH_PX.dp,
        JUDGE_VIEWPORT_HEIGHT_PX.dp,
    )

    private fun assertJudgeViewport() {
        val fixture = compose.onNodeWithTag(VISUAL_FIXTURE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("fixture width was ${fixture.width}px", fixture.width == JUDGE_VIEWPORT_WIDTH_PX.toFloat())
        assertTrue("fixture height was ${fixture.height}px", fixture.height == JUDGE_VIEWPORT_HEIGHT_PX.toFloat())
    }

    private fun assertEveryEnabledActionIsAtLeast48Dp(context: String) {
        val tooSmall = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.width < 48f || it.height < 48f }
        assertTrue("$context has enabled actions smaller than 48dp: $tooSmall", tooSmall.isEmpty())
    }

    private fun SemanticsNodeInteraction.assertWithinFixture() {
        assertIsDisplayed()
        val fixture = compose.onNodeWithTag(VISUAL_FIXTURE_TAG).fetchSemanticsNode().boundsInRoot
        val node = bounds()
        assertTrue("node left clipped: $node outside $fixture", node.left >= fixture.left)
        assertTrue("node top clipped: $node outside $fixture", node.top >= fixture.top)
        assertTrue("node right clipped: $node outside $fixture", node.right <= fixture.right)
        assertTrue("node bottom clipped: $node outside $fixture", node.bottom <= fixture.bottom)
    }

    private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot

    private val demoVehicle = CapabilityStatus(CapabilityState.Available, IntegrationSource.Demo, "차량 상태")
    private val unavailableNavigation = CapabilityStatus(
        CapabilityState.Unavailable,
        IntegrationSource.Unavailable,
        "내비게이션",
    )
}
