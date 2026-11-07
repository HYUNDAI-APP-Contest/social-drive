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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.ThemeMode
import com.socialdrive.app.domain.LiveUnavailableReason
import com.socialdrive.app.domain.RouteConsentState
import com.socialdrive.app.domain.RoutePrivacyStatus
import com.socialdrive.app.domain.RouteLocationConsentPolicy
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.DeleteRequestState
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.data.remote.CloudAuthPhase
import com.socialdrive.app.data.remote.CloudAuthState
import com.socialdrive.app.data.remote.CloudAuthInputPolicy
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
    cloudConnectionStatus: CapabilityStatus = CapabilityStatus(com.socialdrive.app.domain.CapabilityState.Unavailable, com.socialdrive.app.domain.IntegrationSource.Unavailable, "설정 필요"),
    cloudAuthState: CloudAuthState = CloudAuthState(CloudAuthPhase.UNAVAILABLE, "AWS 로그인을 사용할 수 없습니다"),
    onCloudSignIn: (String, CharArray) -> Unit = { _, secret -> secret.fill('\u0000') },
    onCloudConfirm: (CharArray) -> Unit = { secret -> secret.fill('\u0000') },
    onCloudSignOut: () -> Unit = {},
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    routePrivacy: RoutePrivacyStatus = RoutePrivacyStatus(
        RouteSource.DemoPreset,
        RouteSource.LiveUnavailable(LiveUnavailableReason.SDK_COMING_SOON),
        RouteConsentState.NotAsked,
    ),
    privacyMessage: String? = null,
    onWithdrawConsent: () -> Unit = {},
    onEnableLiveRoute: () -> Unit = {},
    onRequestSpeechPermissions: () -> Unit = {},
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
                            onEnableLiveRoute,
                            onDeleteRequest = { showDeleteConfirmation = true },
                        )
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        IntegrationSection(vehicleStatus, navigationStatus, locationStatus, textToSpeechStatus, speechToTextStatus, cloudConnectionStatus, onRequestSpeechPermissions)
                        CloudAuthSection(motion, cloudAuthState, onCloudSignIn, onCloudConfirm, onCloudSignOut)
                        IntegrationBoundaryNotice(vehicleStatus.source == IntegrationSource.Actual)
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
                        onEnableLiveRoute,
                        onDeleteRequest = { showDeleteConfirmation = true },
                    )
                    IntegrationSection(vehicleStatus, navigationStatus, locationStatus, textToSpeechStatus, speechToTextStatus, cloudConnectionStatus, onRequestSpeechPermissions)
                    CloudAuthSection(motion, cloudAuthState, onCloudSignIn, onCloudConfirm, onCloudSignOut)
                    IntegrationBoundaryNotice(vehicleStatus.source == IntegrationSource.Actual)
                }
            }
        }
    }
}

@Composable
private fun CloudAuthSection(
    motion: MotionState,
    authState: CloudAuthState,
    onSignIn: (String, CharArray) -> Unit,
    onConfirm: (CharArray) -> Unit,
    onSignOut: () -> Unit,
) {
    if (authState.phase == CloudAuthPhase.UNAVAILABLE) return
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val editable = motion == MotionState.Parked && authState.phase != CloudAuthPhase.WORKING
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AWS 계정", style = MaterialTheme.typography.titleLarge)
            Text(authState.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (authState.phase) {
                CloudAuthPhase.SIGNED_IN -> Button(
                    onClick = onSignOut,
                    enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("로그아웃") }
                CloudAuthPhase.CHALLENGE -> {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = CloudAuthInputPolicy.limitChallenge(it) },
                        label = { Text(authState.challengeLabel ?: "인증 응답") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = editable,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val response = secret.toCharArray()
                            secret = ""
                            onConfirm(response)
                        },
                        enabled = editable && secret.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("추가 인증") }
                }
                CloudAuthPhase.UNAVAILABLE -> Unit
                else -> {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = CloudAuthInputPolicy.limitUsername(it) },
                        label = { Text("Cognito 아이디") },
                        enabled = editable,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = CloudAuthInputPolicy.limitPassword(it) },
                        label = { Text("비밀번호") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = editable,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val password = secret.toCharArray()
                            secret = ""
                            onSignIn(username, password)
                        },
                        enabled = editable && username.isNotBlank() && secret.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (authState.phase == CloudAuthPhase.WORKING) "확인 중" else "AWS 로그인") }
                }
            }
            Text("비밀번호와 인증코드는 화면 메모리에서 제출 직후 지웁니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onEnableLiveRoute: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var showLiveRouteConsent by remember { mutableStateOf(false) }
    if (showLiveRouteConsent) {
        AlertDialog(
            onDismissRequest = { showLiveRouteConsent = false },
            title = { Text("실시간 경로 수집에 동의할까요?") },
            text = {
                Text(
                    "기록 중에만 PLEOS 위치 SDK로 현재 위치를 처리합니다. " +
                        "원본 위도·경도는 저장하거나 업로드하지 않고, 정규화된 경로 모양과 거리만 기기에 보관합니다. " +
                        "설정에서 언제든 동의를 철회하고 미게시 경로를 삭제할 수 있습니다. " +
                        "동의 정책 ${RouteLocationConsentPolicy.VERSION}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showLiveRouteConsent = false; onEnableLiveRoute() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("동의하고 권한 확인") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLiveRouteConsent = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("동의하지 않음") }
            },
        )
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("경로 데이터 및 동의", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                RouteSourceBadge(routePrivacy.displayedSource)
            }
            Text(if (routePrivacy.displayedSource == RouteSource.LiveAvailable) "실시간 경로 사용 · 원본 좌표 미저장" else "현재 데모 경로만 사용 · 실시간 위치 미사용")
            Text(
                "실시간 경로: ${routePrivacy.liveSource.userLabel()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(routeCapabilityTitle(routePrivacy.liveSource), style = MaterialTheme.typography.titleMedium)
                    Text(routeConsentStorageLabel(routePrivacy.consentState), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (routePrivacy.consentState != RouteConsentState.Granted) {
                    Button(
                        onClick = { showLiveRouteConsent = true },
                        enabled = motion == MotionState.Parked,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("실시간 경로 켜기") }
                }
                if (routePrivacy.consentState == RouteConsentState.Granted) {
                    if (routePermissionRecoveryVisible(routePrivacy.consentState, routePrivacy.liveSource)) {
                        Button(
                            onClick = onEnableLiveRoute,
                            enabled = motion == MotionState.Parked,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("위치 권한 다시 확인") }
                    }
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
    cloudConnectionStatus: CapabilityStatus,
    onRequestSpeechPermissions: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("연동 및 보존 상태", style = MaterialTheme.typography.titleLarge)
            IntegrationRow("차량 상태", vehicleStatus)
            IntegrationRow("차량 내비게이션", navigationStatus)
            IntegrationRow("위치 연동", locationStatus)
            IntegrationRow("음성 안내", textToSpeechStatus)
            IntegrationRow("음성 인식", speechToTextStatus)
            if (
                textToSpeechStatus.capability == CapabilityState.PermissionDenied ||
                speechToTextStatus.capability == CapabilityState.PermissionDenied
            ) {
                TextButton(
                    onClick = onRequestSpeechPermissions,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("음성 권한 다시 확인") }
            }
            IntegrationRow("AWS 클라우드", cloudConnectionStatus)
            StatusRow("차량 제어", "안전 정책 · 읽기 전용")
            StatusRow("오프라인 초안", "기기에 보존")
        }
    }
}

@Composable
private fun IntegrationBoundaryNotice(actualMode: Boolean) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (actualMode) "PLEOS 실제 SDK 연동 모드" else "데모 데이터 · 외부 연동 없음",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (actualMode) {
                        "원본 위치와 사용자 토큰은 화면·Room·로그에 저장하지 않습니다."
                    } else {
                        "AWS 키, 사용자 토큰, 실제 위치는 포함되지 않습니다."
                    },
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

internal fun routeCapabilityTitle(source: RouteSource): String = when (source) {
    RouteSource.LiveAvailable -> "PLEOS 위치 SDK 사용 가능"
    RouteSource.DemoPreset -> "데모 경로 모드"
    is RouteSource.LiveUnavailable -> when (source.reason) {
        LiveUnavailableReason.PERMISSION_DENIED -> "PLEOS 위치 권한 필요"
        LiveUnavailableReason.CAPABILITY_MISSING -> "PLEOS 위치 기능 사용 불가"
        LiveUnavailableReason.SDK_COMING_SOON -> "PLEOS 위치 기능 준비 중"
    }
}

internal fun routePermissionRecoveryVisible(consentState: RouteConsentState, liveSource: RouteSource): Boolean =
    consentState == RouteConsentState.Granted &&
        liveSource == RouteSource.LiveUnavailable(LiveUnavailableReason.PERMISSION_DENIED)

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

private fun RouteSource.userLabel() = when (this) {
    RouteSource.LiveAvailable -> "사용 가능"
    is RouteSource.LiveUnavailable -> reason.userLabel()
    RouteSource.DemoPreset -> "데모 경로"
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
