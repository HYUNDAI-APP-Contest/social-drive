package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.ui.components.IntegrationStatusChip
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@Composable
fun SettingsScreen(
    motion: MotionState,
    theme: ThemeMode,
    vehicleStatus: CapabilityStatus,
    navigationStatus: CapabilityStatus,
    locationStatus: CapabilityStatus = CapabilityStatus(com.socialdrive.app.domain.CapabilityState.Unavailable, com.socialdrive.app.domain.IntegrationSource.Unavailable, "사용 불가"),
    textToSpeechStatus: CapabilityStatus = CapabilityStatus(com.socialdrive.app.domain.CapabilityState.Unavailable, com.socialdrive.app.domain.IntegrationSource.Unavailable, "사용 불가"),
    speechToTextStatus: CapabilityStatus = CapabilityStatus(com.socialdrive.app.domain.CapabilityState.Unavailable, com.socialdrive.app.domain.IntegrationSource.Unavailable, "사용 불가"),
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    routePrivacy: RoutePrivacyStatus = RoutePrivacyStatus(
        RouteSource.DemoPreset,
        RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON),
        RouteConsentState.NotAsked,
    ),
    privacyMessage: String? = null,
    onWithdrawConsent: () -> Unit = {},
    hasPublishedDraft: Boolean = false,
    deleteRequestState: DeleteRequestState? = null,
    onConfirmPublishedDelete: () -> Unit = {},
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("게시된 기록 삭제를 요청할까요?") },
            text = { Text("삭제 요청을 기기에 먼저 저장합니다. 서버가 204로 확인하기 전에는 삭제 완료로 표시하지 않고 로컬 원본도 유지합니다.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false; onConfirmPublishedDelete() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("삭제 대기 등록") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("취소") }
            },
        )
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로가기")
            }
            Column {
                Text("안전 및 개인정보 설정", style = MaterialTheme.typography.headlineMedium)
                Text("연동 상태를 한눈에 확인하세요", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (motion != MotionState.Parked) SafetyRestrictionBanner("주행 중에는 설정을 변경할 수 없어요.")
        BoxWithConstraints(Modifier.weight(1f)) {
            val wideLayout = maxWidth >= 900.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ThemeSection(theme, motion, onThemeChange)
                        PrivacySection(
                            routePrivacy, motion, privacyMessage, hasPublishedDraft,
                            deleteRequestState, onWithdrawConsent,
                            onDeleteRequest = { showDeleteConfirmation = true },
                        )
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        IntegrationSection(vehicleStatus, navigationStatus, locationStatus, textToSpeechStatus, speechToTextStatus)
                        DemoBoundaryNotice()
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ThemeSection(theme, motion, onThemeChange)
                    PrivacySection(
                        routePrivacy, motion, privacyMessage, hasPublishedDraft,
                        deleteRequestState, onWithdrawConsent,
                        onDeleteRequest = { showDeleteConfirmation = true },
                    )
                    IntegrationSection(vehicleStatus, navigationStatus, locationStatus, textToSpeechStatus, speechToTextStatus)
                    DemoBoundaryNotice()
                }
            }
        }
    }
}

@Composable
private fun ThemeSection(theme: ThemeMode, motion: MotionState, onThemeChange: (ThemeMode) -> Unit) {
    SettingSection("화면 테마") {
        ThemeMode.entries.forEach { value ->
            FilterChip(
                selected = theme == value,
                enabled = motion == MotionState.Parked,
                onClick = { onThemeChange(value) },
                label = { Text(value.label()) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun PrivacySection(
    routePrivacy: RoutePrivacyStatus,
    motion: MotionState,
    privacyMessage: String?,
    hasPublishedDraft: Boolean,
    deleteRequestState: DeleteRequestState?,
    onWithdrawConsent: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("경로 데이터 및 동의", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                RouteSourceBadge(routePrivacy.displayedSource)
            }
            Text("현재 데모 경로만 사용 · 실시간 위치 미사용")
            Text(
                "실시간 경로: ${routePrivacy.liveSource.reason.userLabel()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("실시간 경로 SDK 준비 중", style = MaterialTheme.typography.titleMedium)
                    Text(routeConsentStorageLabel(routePrivacy.consentState), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (routePrivacy.consentState == RouteConsentState.Granted) {
                    Button(
                        onClick = onWithdrawConsent,
                        enabled = motion == MotionState.Parked,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("동의 철회") }
                }
                if (hasPublishedDraft) {
                    Button(
                        onClick = onDeleteRequest,
                        enabled = motion == MotionState.Parked && deleteRequestState == null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(deleteRequestState?.buttonLabel() ?: "게시 기록 삭제 요청") }
                }
            }
            deleteRequestState?.let { state ->
                Text(state.statusLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (privacyMessage != null) SafetyRestrictionBanner(privacyMessage)
        }
    }
}

@Composable
private fun IntegrationSection(
    vehicleStatus: CapabilityStatus,
    navigationStatus: CapabilityStatus,
    locationStatus: CapabilityStatus,
    textToSpeechStatus: CapabilityStatus,
    speechToTextStatus: CapabilityStatus,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("연동 및 보존 상태", style = MaterialTheme.typography.titleLarge)
            IntegrationRow("차량 상태", vehicleStatus)
            IntegrationRow("차량 내비게이션", navigationStatus)
            IntegrationRow("위치 연동", locationStatus)
            IntegrationRow("음성 안내", textToSpeechStatus)
            IntegrationRow("음성 인식", speechToTextStatus)
            UnavailableStatusRow("차량 제어", "준비 중 · 사용 불가")
            StatusRow("오프라인 초안", "기기에 보존")
        }
    }
}

@Composable
private fun DemoBoundaryNotice() {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("데모 데이터 · 외부 연동 없음", style = MaterialTheme.typography.titleMedium)
                Text(
                    "AWS 키, 사용자 토큰, 실제 위치는 포함되지 않습니다.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

internal fun routeConsentStorageLabel(consentState: RouteConsentState): String =
    if (consentState == RouteConsentState.Granted) {
        "현재 수집하지 않음 · 기존 동의 저장됨"
    } else {
        "현재 수집하지 않음 · 동의도 저장하지 않음"
    }

@Composable
private fun IntegrationRow(label: String, status: CapabilityStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IntegrationStatusChip(status, contextLabel = label)
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun UnavailableStatusRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = "준비 중", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ThemeMode.label() = when (this) { ThemeMode.System -> "시스템"; ThemeMode.Day -> "주간"; ThemeMode.Night -> "야간" }
private fun LiveUnavailableReason.userLabel() = when (this) {
    LiveUnavailableReason.SDK_COMING_SOON -> "SDK 준비 중 · 향후 제공 예정"
    LiveUnavailableReason.PERMISSION_DENIED -> "권한이 거부됨"
    LiveUnavailableReason.CAPABILITY_MISSING -> "차량 기능 미지원"
}

private fun DeleteRequestState.buttonLabel() = when (this) {
    DeleteRequestState.DELETE_PENDING, DeleteRequestState.RUNNING, DeleteRequestState.RETRY_WAIT -> "삭제 처리 대기 중"
    DeleteRequestState.CONFIRMED -> "서버 삭제 확인됨"
    else -> "삭제 자동 처리 중지됨"
}

private fun DeleteRequestState.statusLabel() = when (this) {
    DeleteRequestState.DELETE_PENDING -> "삭제 요청을 기기에 저장했습니다. 연결되면 안전하게 전송합니다."
    DeleteRequestState.RUNNING -> "삭제 확인을 요청하고 있습니다."
    DeleteRequestState.RETRY_WAIT -> "연결을 확인한 뒤 최대 4회 안에서 다시 시도합니다."
    DeleteRequestState.CONFIRMED -> "서버가 삭제를 확인했습니다. 로컬 원본은 기기에 유지됩니다."
    DeleteRequestState.NOT_FOUND_UNCONFIRMED -> "서버에서 기록을 찾지 못했습니다. 삭제 완료로 간주하지 않았습니다."
    DeleteRequestState.AUTH_REQUIRED -> "로그인 또는 권한 확인이 필요해 자동 재시도를 중지했습니다."
    DeleteRequestState.VERSION_CONFLICT -> "서버 변경이 있어 자동 삭제를 중지했습니다."
    DeleteRequestState.FAILED_PERMANENT -> "최대 시도 횟수에 도달했습니다. 로컬 원본은 유지됩니다."
    DeleteRequestState.LEGACY_UNREVIEWABLE -> "이전 삭제 요청은 안전 식별 정보가 없어 자동 전송하지 않습니다."
}
