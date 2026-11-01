package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
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
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.ui.components.IntegrationStatusChip
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@Composable
fun SettingsScreen(
    motion: MotionState,
    theme: ThemeMode,
    vehicleStatus: CapabilityStatus,
    navigationStatus: CapabilityStatus,
    onMotionChange: (MotionState) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    routePrivacy: RoutePrivacyStatus = RoutePrivacyStatus(
        RouteSource.DemoPreset,
        RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON),
        RouteConsentState.NotAsked,
    ),
    privacyMessage: String? = null,
    onRequestLiveConsent: () -> Unit = {},
    onWithdrawConsent: () -> Unit = {},
    hasPublishedDraft: Boolean = false,
    deletePending: Boolean = false,
    onConfirmPublishedDelete: () -> Unit = {},
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("게시된 기록 삭제를 요청할까요?") },
            text = { Text("로컬에는 삭제 대기 상태만 안전하게 저장합니다. 실제 서버 삭제는 연결 후 별도 확인이 필요합니다.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirmation = false; onConfirmPublishedDelete() }) { Text("삭제 대기 등록") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("취소") } },
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column {
                Text("안전 및 개인정보 설정", style = MaterialTheme.typography.headlineMedium)
                Text("연동 상태를 한눈에 확인하세요", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (motion != MotionState.Parked) SafetyRestrictionBanner("주행 중에는 설정을 변경할 수 없어요.")
        SettingSection("화면 테마") {
            ThemeMode.entries.forEach { value ->
                FilterChip(selected = theme == value, enabled = motion == MotionState.Parked, onClick = { onThemeChange(value) }, label = { Text(value.label()) })
            }
        }
        SettingSection("차량 상태 시뮬레이션") {
            MotionState.entries.forEach { value ->
                FilterChip(
                    selected = motion == value,
                    enabled = vehicleStatus.source == IntegrationSource.Demo && motion == MotionState.Parked,
                    onClick = { onMotionChange(value) },
                    label = { Text(value.label()) },
                )
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("경로 데이터 및 동의", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    RouteSourceBadge(routePrivacy.displayedSource)
                }
                Text("현재 화면의 모든 경로는 데모 데이터입니다. 실제 위치는 수집하지 않습니다.")
                Text(
                    "실시간 경로: ${routePrivacy.liveSource.reason.userLabel()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("동의 상태: ${routePrivacy.consentState.userLabel()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onRequestLiveConsent,
                        enabled = motion == MotionState.Parked && routePrivacy.consentState != RouteConsentState.Granted,
                    ) { Text("실시간 경로 사용 확인") }
                    if (routePrivacy.consentState == RouteConsentState.Granted) {
                        Button(onClick = onWithdrawConsent, enabled = motion == MotionState.Parked) { Text("동의 철회") }
                    }
                    if (hasPublishedDraft) {
                        Button(
                            onClick = { showDeleteConfirmation = true },
                            enabled = motion == MotionState.Parked && !deletePending,
                        ) { Text(if (deletePending) "삭제 대기 중" else "게시 기록 삭제 요청") }
                    }
                }
                if (privacyMessage != null) SafetyRestrictionBanner(privacyMessage)
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IntegrationRow("Vehicle State", vehicleStatus)
                IntegrationRow("NaviHelper", navigationStatus)
                StatusRow("Fused Location", "Coming Soon")
                StatusRow("TTS / STT", "Coming Soon")
                StatusRow("Vehicle Control", "Coming Soon")
                StatusRow("오프라인 초안", "0개")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("AWS 키, 사용자 토큰, 실제 위치는 앱 데모에 포함되지 않습니다.")
        }
    }
}

@Composable
private fun IntegrationRow(label: String, status: CapabilityStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IntegrationStatusChip(status)
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
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text(" $label", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ThemeMode.label() = when (this) { ThemeMode.System -> "시스템"; ThemeMode.Day -> "주간"; ThemeMode.Night -> "야간" }
private fun MotionState.label() = when (this) { MotionState.Parked -> "주차"; MotionState.Driving -> "주행"; MotionState.Unknown -> "확인 중" }

private fun LiveUnavailableReason.userLabel() = when (this) {
    LiveUnavailableReason.SDK_COMING_SOON -> "SDK 준비 중 · Coming Soon"
    LiveUnavailableReason.PERMISSION_DENIED -> "권한이 거부됨"
    LiveUnavailableReason.CAPABILITY_MISSING -> "차량 기능 미지원"
}

private fun RouteConsentState.userLabel() = when (this) {
    RouteConsentState.NotAsked -> "동의하지 않음"
    RouteConsentState.Granted -> "동의 완료"
    RouteConsentState.Withdrawn -> "철회됨"
}
