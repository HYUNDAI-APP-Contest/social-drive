package com.socialdrive.app

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.ui.screens.RecordingScreen
import com.socialdrive.app.ui.screens.RecordingStopDialog
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualAccessibilityHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyRepresentativeActionMeetsAutomotiveTouchTargets() {
        val show = installFixture()
        VisualScenario.entries.forEach { scenario ->
            show(scenario, 1f)
            val tooSmall = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .filter { it.width < 48f || it.height < 48f }
            assertTrue("${scenario.fixtureId} has actions smaller than 48dp: $tooSmall", tooSmall.isEmpty())
        }

        show(VisualScenario.HomeContent, 1f)
        compose.onNodeWithText("기록 시작", substring = true).assertHeightIsAtLeast(64.dp)
        show(VisualScenario.RecordingActive, 1f)
        compose.onNodeWithText("방금 좋았어", substring = true).assertHeightIsAtLeast(72.dp)
        compose.onNodeWithText("기록 종료", substring = true).assertHeightIsAtLeast(72.dp)
        show(VisualScenario.MemoryDetailDemo, 1f)
        compose.onNodeWithText("차량 내비게이션 연결 안내", substring = true).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("이 드라이브 게시하기").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun largeTextKeepsPrimaryCopyAndActionsInsideJudgeViewport() {
        val show = installFixture(fontScale = 1.3f)
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
            show(scenario, 1.3f)
            val primaryNode = compose.onNodeWithText(primary, substring = scenario == VisualScenario.RecordingActive)
            val secondaryNode = compose.onNodeWithText(secondary, substring = true)
            primaryNode.assertWithinFixture()
            secondaryNode.assertWithinFixture()
            assertFalse("$scenario primary copy overlaps its action/detail", primaryNode.bounds().overlaps(secondaryNode.bounds()))
        }
    }

    @Test
    fun largeTextConsentOverviewShowsAllJudgeCriticalFactsWithoutScrolling() {
        installFixture(VisualScenario.ConsentLiveUnavailable, fontScale = 1.3f)
        listOf(
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
            try {
                compose.onNodeWithText(text).assertWithinFixture()
            } catch (error: AssertionError) {
                throw AssertionError("Judge-critical text was not fully visible: $text", error)
            }
        }
        compose.onNodeWithText("0개", substring = true).assertDoesNotExist()
    }

    @Test
    fun largeTextOfflineHeaderRemainsVisibleWithoutDeadTagCopy() {
        installFixture(VisualScenario.HomeOfflineCached, fontScale = 1.3f)
        compose.onNodeWithText("오늘의 드라이브").assertWithinFixture()
        compose.onNodeWithText("기록 시작", substring = true).assertWithinFixture()
        compose.onNodeWithContentDescription("설정 열기").assertWithinFixture()
        compose.onNodeWithText("카드 태그", substring = true).assertDoesNotExist()
        compose.onNodeWithText("오프라인 · 저장된 기록 표시 중").assertWithinFixture()
        compose.onNodeWithText("연결되면 최신 목록을 다시 확인합니다.").assertWithinFixture()
    }

    @Test
    fun everyHomeStateKeepsTheStandardHeaderAcrossDayNightAndLargeText() {
        val scenarioState = mutableStateOf(VisualScenario.HomeContent)
        val themeState = mutableStateOf(ThemeMode.Day)
        val fontScaleState = mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScaleState.value)) {
                VisualScenarioFixture(
                    scenario = scenarioState.value,
                    theme = themeState.value,
                    modifier = Modifier.requiredSize(
                        JUDGE_VIEWPORT_WIDTH_PX.dp,
                        JUDGE_VIEWPORT_HEIGHT_PX.dp,
                    ),
                )
            }
        }
        val homeStates = listOf(
            VisualScenario.HomeContent,
            VisualScenario.HomeLoading,
            VisualScenario.HomeEmpty,
            VisualScenario.HomeOfflineCached,
            VisualScenario.HomeError,
        )
        val variants = listOf(
            ThemeMode.Day to 1f,
            ThemeMode.Night to 1f,
            ThemeMode.Day to 1.3f,
        )
        homeStates.forEach { scenario ->
            variants.forEach { (theme, fontScale) ->
                compose.runOnIdle {
                    scenarioState.value = scenario
                    themeState.value = theme
                    fontScaleState.value = fontScale
                }
                compose.waitForIdle()
                compose.onNodeWithText("오늘의 드라이브").assertWithinFixture()
                compose.onNodeWithText("기록 시작", substring = true).assertWithinFixture()
                compose.onNodeWithText("데모 차량 상태").assertWithinFixture()
                compose.onNodeWithContentDescription("설정 열기").assertWithinFixture()
            }
        }
    }

    @Test
    fun highlightCaptureShowsImmediateMemoryFeedback() {
        installFixture(VisualScenario.RecordingActive)
        compose.onNodeWithText("방금 좋았어", substring = true).performClick()
        compose.onNodeWithText("순간 4 저장됨 · 주차 후 이야기에서 확인").assertIsDisplayed()
    }

    @Test
    fun detailMomentSelectionUpdatesTheStoryArtwork() {
        installFixture(VisualScenario.MemoryDetailDemo)
        compose.onNodeWithContentDescription("등대가 보이는 길 순간 보기").performClick()
        compose.onNodeWithText("순간 2 · 18:03").assertIsDisplayed()
    }

    @Test
    fun optionalSettingsActionsMeet48Dp() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                SocialDriveTheme(ThemeMode.Day) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.requiredSize(
                            JUDGE_VIEWPORT_WIDTH_PX.dp,
                            JUDGE_VIEWPORT_HEIGHT_PX.dp,
                        ).testTag(VISUAL_FIXTURE_TAG),
                    ) {
                        SettingsScreen(
                            motion = MotionState.Parked,
                            theme = ThemeMode.Day,
                            vehicleStatus = CapabilityStatus(
                                CapabilityState.Available,
                                IntegrationSource.Demo,
                                "차량 상태",
                            ),
                            navigationStatus = CapabilityStatus(
                                CapabilityState.Unavailable,
                                IntegrationSource.Unavailable,
                                "내비게이션",
                            ),
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
            }
        }
        compose.waitForIdle()
        listOf("시스템", "주간", "야간", "동의 철회", "게시 기록 삭제 요청").forEach { label ->
            compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
        }
        val tooSmall = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.width < 48f || it.height < 48f }
        assertTrue("optional settings actions smaller than 48dp: $tooSmall", tooSmall.isEmpty())
    }

    @Test
    fun openedRecordingDialogActionsMeet48Dp() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                SocialDriveTheme(ThemeMode.Day) {
                    var showStopDialog by remember { mutableStateOf(false) }
                    RecordingScreen(
                        snapshot = RecordingSnapshot(
                            storyId = "demo-coast",
                            route = com.socialdrive.app.data.fake.FakeDriveRepository().drive("demo-coast")!!.route,
                            elapsed = "01:24:17",
                            distance = "64.2 km",
                            highlights = 3,
                            gps = CapabilityState.Unavailable,
                            vehicle = CapabilityState.Unavailable,
                            routeSource = RouteSource.DemoPreset,
                        ),
                        onHighlight = {},
                        onRequestStop = { showStopDialog = true },
                    )
                    if (showStopDialog) {
                        RecordingStopDialog(
                            motion = MotionState.Parked,
                            onDismiss = { showStopDialog = false },
                            onConfirm = { showStopDialog = false },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("기록 종료", substring = true).performClick()
        compose.onNodeWithText("초안으로 저장").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("계속 기록").assertHeightIsAtLeast(48.dp).performClick()
    }

    @Test
    fun openedNavigationGuidanceActionMeets48Dp() {
        installFixture(VisualScenario.MemoryDetailDemo)
        compose.onNodeWithText("차량 내비게이션 연결 안내", substring = true).performClick()
        compose.onNodeWithText("NaviHelper 안내").assertWithinFixture()
        compose.onNodeWithText("확인").assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun installFixture(
        scenario: VisualScenario = VisualScenario.HomeContent,
        fontScale: Float = 1f,
    ): (VisualScenario, Float) -> Unit {
        val scenarioState = mutableStateOf(scenario)
        val fontScaleState = mutableStateOf(fontScale)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScaleState.value)) {
                VisualScenarioFixture(
                    scenario = scenarioState.value,
                    theme = ThemeMode.Day,
                    modifier = Modifier.requiredSize(
                        JUDGE_VIEWPORT_WIDTH_PX.dp,
                        JUDGE_VIEWPORT_HEIGHT_PX.dp,
                    ),
                )
            }
        }
        compose.waitForIdle()
        assertJudgeViewport()
        return { nextScenario, nextFontScale ->
            compose.runOnIdle {
                scenarioState.value = nextScenario
                fontScaleState.value = nextFontScale
            }
            compose.waitForIdle()
            assertJudgeViewport()
        }
    }

    private fun assertJudgeViewport() {
        val fixture = compose.onNodeWithTag(VISUAL_FIXTURE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("fixture width was ${fixture.width}px", fixture.width == JUDGE_VIEWPORT_WIDTH_PX.toFloat())
        assertTrue("fixture height was ${fixture.height}px", fixture.height == JUDGE_VIEWPORT_HEIGHT_PX.toFloat())
    }

    private fun SemanticsNodeInteraction.assertWithinFixture() {
        val fixture = compose.onNodeWithTag(VISUAL_FIXTURE_TAG).fetchSemanticsNode().boundsInRoot
        val node = bounds()
        assertTrue("node has no rendered area: $node", node.width > 0f && node.height > 0f)
        assertTrue("node left clipped: $node outside $fixture", node.left >= fixture.left)
        assertTrue("node top clipped: $node outside $fixture", node.top >= fixture.top)
        assertTrue("node right clipped: $node outside $fixture", node.right <= fixture.right)
        assertTrue("node bottom clipped: $node outside $fixture", node.bottom <= fixture.bottom)
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot
}
