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
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.safety.SafetyPolicy
import com.socialdrive.app.ui.screens.DetailScreen
import com.socialdrive.app.ui.screens.HomeScreen
import com.socialdrive.app.ui.screens.PublishScreen
import com.socialdrive.app.ui.screens.RecordingScreen
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

    BackHandler(destination != AppDestination.Home) { destination = AppDestination.Home }

    SocialDriveTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (destination) {
                    AppDestination.Home -> HomeScreen(
                        state = ContentState.Content(repository.drives()),
                        blockedMessage = blockedMessage,
                        onDetail = { selectedDrive = it; navigate(AppDestination.Detail) },
                        onRecord = { navigate(AppDestination.Recording) },
                        onSettings = { navigate(AppDestination.Settings) },
                        onRetry = {},
                    )
                    AppDestination.Detail -> DetailScreen(
                        drive = requireNotNull(repository.drive(selectedDrive)),
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
                        onStop = { navigate(AppDestination.Home) },
                    )
                    AppDestination.Publish -> PublishScreen(
                        canEdit = SafetyPolicy.canEdit(motion),
                        onBack = { navigate(AppDestination.Detail) },
                        onPublished = { navigate(AppDestination.Home) },
                    )
                    AppDestination.Settings -> SettingsScreen(
                        motion = motion,
                        theme = theme,
                        onMotionChange = { motion = it },
                        onThemeChange = { theme = it },
                        onBack = { navigate(AppDestination.Home) },
                    )
                }
            }
        }
    }
}
