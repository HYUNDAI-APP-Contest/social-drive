package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.SpeechRecognitionState
import com.socialdrive.app.ui.components.MemoryPalette
import com.socialdrive.app.ui.components.RouteMemoryArtwork
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.components.SafetyRestrictionBanner
import com.socialdrive.app.ui.components.UploadStateChip

private val PublishDemoRoute = listOf(
    RoutePoint(.06f, .72f), RoutePoint(.18f, .58f), RoutePoint(.31f, .63f),
    RoutePoint(.45f, .39f), RoutePoint(.62f, .45f), RoutePoint(.78f, .24f), RoutePoint(.94f, .32f),
)

@Composable
fun PublishScreen(
    canEdit: Boolean,
    uploadState: UploadState,
    onUploadStateChange: (UploadState) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    initialTitle: String = "빛나는 해안도로의 저녁",
    initialDescription: String = "바다를 따라 만난 좋은 순간을 나눠요.",
    onCopyChanged: (String, String) -> Unit = { _, _ -> },
    canAct: Boolean = canEdit,
    editingRestriction: String = "주차 상태를 확인한 뒤 게시물을 편집할 수 있어요.",
    routeSource: RouteSource = RouteSource.DemoPreset,
    onReviewConflict: () -> Unit = {},
    route: List<RoutePoint> = PublishDemoRoute,
    highlightCount: Int = 4,
    memoryPalette: MemoryPalette = MemoryPalette.Coast,
    speechRecognition: CapabilityState = CapabilityState.Unavailable,
    speechRecognitionActive: Boolean = false,
    speechRecognitionState: SpeechRecognitionState = SpeechRecognitionState.Idle,
    onDictateTitle: () -> Boolean = { false },
    onDictateDescription: () -> Boolean = { false },
    onCancelDictation: () -> Unit = {},
    publicationBlockReason: String? = null,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var consent by remember { mutableStateOf(false) }
    val currentRecognitionActive by rememberUpdatedState(speechRecognitionActive)
    val currentCancelDictation by rememberUpdatedState(onCancelDictation)
    DisposableEffect(Unit) {
        onDispose {
            if (currentRecognitionActive) currentCancelDictation()
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로가기")
            }
            Column(Modifier.weight(1f)) {
                Text("드라이브 게시", style = MaterialTheme.typography.headlineMedium)
                Text("대표 이미지 → 이야기 → 공개 범위 → 동의", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UploadStateChip(uploadState)
            RouteSourceBadge(routeSource, Modifier.padding(start = 10.dp))
        }
        if (!canEdit) SafetyRestrictionBanner(editingRestriction, Modifier.padding(vertical = 12.dp))
        if (publicationBlockReason != null) {
            SafetyRestrictionBanner(publicationBlockReason, Modifier.padding(vertical = 12.dp))
        }
        Row(
            Modifier.fillMaxSize().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("1  대표 장면", style = MaterialTheme.typography.titleLarge)
                androidx.compose.material3.Card(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        RouteMemoryArtwork(
                            points = route,
                            palette = memoryPalette,
                            momentCount = highlightCount,
                            selectedMomentIndex = (highlightCount - 1).takeIf { it >= 0 },
                            routeSource = routeSource,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                RouteSourceBadge(routeSource)
                                Text("좋았던 순간을 한 장의 이야기로", style = MaterialTheme.typography.titleLarge)
                                Text(publishCoverPrivacyLabel(routeSource), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("2  나의 이야기", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        title,
                        {
                            title = it.take(40)
                            onCopyChanged(title, description)
                        },
                        Modifier.weight(1f),
                        enabled = canEdit,
                        label = { Text("제목") },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = { onDictateTitle() },
                        enabled = canEdit && speechRecognition == CapabilityState.Available && !speechRecognitionActive,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Rounded.Mic, contentDescription = "음성으로 제목 입력") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        description,
                        {
                            description = it.take(120)
                            onCopyChanged(title, description)
                        },
                        Modifier.weight(1f),
                        enabled = canEdit,
                        label = { Text("짧은 설명") },
                        minLines = 3,
                    )
                    IconButton(
                        onClick = { onDictateDescription() },
                        enabled = canEdit && speechRecognition == CapabilityState.Available && !speechRecognitionActive,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Rounded.Mic, contentDescription = "음성으로 설명 입력") }
                }
                if (speechRecognitionActive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("음성을 듣고 있어요…", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = onCancelDictation, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("음성 입력 취소")
                        }
                    }
                } else {
                    speechRecognitionMessage(speechRecognitionState)?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("3  공개 범위 · 전체 공개", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = consent,
                        onCheckedChange = { consent = it },
                        enabled = canAct,
                        modifier = Modifier.size(48.dp),
                    )
                    Column {
                        Text("게시할 항목을 확인했습니다.")
                        Text(publishSharedItemsLabel(routeSource), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("정밀 위치와 원시 차량 상태는 제외됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (uploadState) {
                    UploadState.Published -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("피드에서 확인하기") }
                    UploadState.Uploading -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("안전하게 업로드 중…") }
                    UploadState.Failed -> Button(onClick = { onUploadStateChange(UploadState.Uploading) }, enabled = canAct && consent && publicationBlockReason == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("업로드 다시 시도") }
                    UploadState.NeedsConflictReview -> Button(onClick = onReviewConflict, enabled = canAct, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("서버 버전 검토") }
                    UploadState.Queued -> Button(onClick = { onUploadStateChange(UploadState.Uploading) }, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("네트워크 연결을 기다리는 중") }
                    UploadState.Draft -> Button(onClick = { onUploadStateChange(UploadState.Uploading) }, enabled = canEdit && consent && publicationBlockReason == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("안전하게 게시하기") }
                }
            }
        }
    }
}

internal fun speechRecognitionMessage(state: SpeechRecognitionState): String? = when (state) {
    SpeechRecognitionState.Idle, SpeechRecognitionState.Listening -> null
    SpeechRecognitionState.Applied -> "음성 인식 결과를 반영했어요."
    SpeechRecognitionState.NoResult -> "인식된 음성이 없어요. 다시 시도해 주세요."
    SpeechRecognitionState.Cancelled -> "음성 입력을 취소했어요."
    SpeechRecognitionState.Failed -> "음성 인식을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."
}

internal fun cloudPublicationBlockReason(isLocalDraft: Boolean, status: CapabilityStatus): String? {
    if (!isLocalDraft || status.capability == CapabilityState.Available) return null
    return "${status.label}. 설정에서 AWS 연결 상태를 확인해 주세요."
}

internal fun publishCoverPrivacyLabel(source: RouteSource): String =
    if (source == RouteSource.LiveAvailable) "원본 위치와 사진은 포함되지 않습니다"
    else "실제 위치와 사진은 포함되지 않습니다"

internal fun publishSharedItemsLabel(source: RouteSource): String =
    if (source == RouteSource.LiveAvailable) "정규화 경로 아트워크 · 제목과 설명 · 하이라이트 개수"
    else "데모 경로 아트워크 · 제목과 설명 · 하이라이트 개수"
