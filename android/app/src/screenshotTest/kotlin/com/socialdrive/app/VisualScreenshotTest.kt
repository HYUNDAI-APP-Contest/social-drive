package com.socialdrive.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.ui.visual.VisualScenario
import com.socialdrive.app.ui.visual.VisualScenarioFixture

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    device = "spec:width=1280px,height=720px,dpi=160",
    locale = "ko-rKR",
    showBackground = true,
)
private annotation class JudgeLandscape

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(
    device = "spec:width=1280px,height=720px,dpi=160",
    locale = "ko-rKR",
    fontScale = 1.3f,
    showBackground = true,
)
private annotation class JudgeLandscapeLargeText

@PreviewTest @JudgeLandscape @Composable fun HomeContentDay() = fixture(VisualScenario.HomeContent, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun HomeContentNight() = fixture(VisualScenario.HomeContent, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun RecordingActiveDay() = fixture(VisualScenario.RecordingActive, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun RecordingActiveNight() = fixture(VisualScenario.RecordingActive, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun ConsentLiveUnavailableDay() = fixture(VisualScenario.ConsentLiveUnavailable, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun ConsentLiveUnavailableNight() = fixture(VisualScenario.ConsentLiveUnavailable, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun MemoryDetailDemoDay() = fixture(VisualScenario.MemoryDetailDemo, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun MemoryDetailDemoNight() = fixture(VisualScenario.MemoryDetailDemo, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun HomeLoadingDay() = fixture(VisualScenario.HomeLoading, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun HomeLoadingNight() = fixture(VisualScenario.HomeLoading, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun HomeEmptyDay() = fixture(VisualScenario.HomeEmpty, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun HomeEmptyNight() = fixture(VisualScenario.HomeEmpty, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun HomeOfflineCachedDay() = fixture(VisualScenario.HomeOfflineCached, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun HomeOfflineCachedNight() = fixture(VisualScenario.HomeOfflineCached, ThemeMode.Night)
@PreviewTest @JudgeLandscape @Composable fun HomeErrorDay() = fixture(VisualScenario.HomeError, ThemeMode.Day)
@PreviewTest @JudgeLandscape @Composable fun HomeErrorNight() = fixture(VisualScenario.HomeError, ThemeMode.Night)

@PreviewTest @JudgeLandscapeLargeText @Composable fun HomeContentLargeText() = fixture(VisualScenario.HomeContent, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun RecordingActiveLargeText() = fixture(VisualScenario.RecordingActive, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun ConsentLiveUnavailableLargeText() = fixture(VisualScenario.ConsentLiveUnavailable, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun MemoryDetailDemoLargeText() = fixture(VisualScenario.MemoryDetailDemo, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun HomeLoadingLargeText() = fixture(VisualScenario.HomeLoading, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun HomeEmptyLargeText() = fixture(VisualScenario.HomeEmpty, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun HomeOfflineCachedLargeText() = fixture(VisualScenario.HomeOfflineCached, ThemeMode.Day)
@PreviewTest @JudgeLandscapeLargeText @Composable fun HomeErrorLargeText() = fixture(VisualScenario.HomeError, ThemeMode.Day)

@Composable
private fun fixture(scenario: VisualScenario, theme: ThemeMode) = VisualScenarioFixture(scenario, theme)
