package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.components.SafetyRestrictionBanner
import com.socialdrive.app.ui.components.UploadStateChip

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
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var consent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column(Modifier.weight(1f)) {
                Text("드라이브 게시", style = MaterialTheme.typography.headlineMedium)
                Text("대표 이미지 → 이야기 → 공개 범위 → 동의", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UploadStateChip(uploadState)
            RouteSourceBadge(routeSource, Modifier.padding(start = 10.dp))
        }
        if (!canEdit) SafetyRestrictionBanner(editingRestriction, Modifier.padding(vertical = 12.dp))
        Row(
            Modifier.fillMaxSize().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("1  대표 장면", style = MaterialTheme.typography.titleLarge)
                androidx.compose.material3.Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("데모 데이터 · ROUTE ARTWORK", color = MaterialTheme.colorScheme.secondary)
                        Text("실제 위치와 사진은 포함되지 않습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("2  나의 이야기", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    title,
                    {
                        title = it.take(40)
                        onCopyChanged(title, description)
                    },
                    Modifier.fillMaxWidth(),
                    enabled = canEdit,
                    label = { Text("제목") },
                    singleLine = true,
                )
                OutlinedTextField(
                    description,
                    {
                        description = it.take(120)
                        onCopyChanged(title, description)
                    },
                    Modifier.fillMaxWidth(),
                    enabled = canEdit,
                    label = { Text("짧은 설명") },
                    minLines = 3,
                )
                Text("3  공개 범위 · 전체 공개", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = canAct)
                    Column {
                        Text("게시할 항목을 확인했습니다.")
                        Text("데모 경로 아트워크 · 제목과 설명 · 하이라이트 개수", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("정밀 위치와 원시 차량 상태는 제외됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (uploadState) {
                    UploadState.Published -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("피드에서 확인하기") }
                    UploadState.Uploading -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("안전하게 업로드 중…") }
                    UploadState.Failed -> Button(onClick = { onUploadStateChange(UploadState.Uploading) }, enabled = canAct && consent, modifier = Modifier.fillMaxWidth()) { Text("업로드 다시 시도") }
                    UploadState.NeedsConflictReview -> Button(onClick = onReviewConflict, enabled = canAct, modifier = Modifier.fillMaxWidth()) { Text("서버 버전 검토") }
                    UploadState.Queued -> Button(onClick = { onUploadStateChange(UploadState.Uploading) }, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("네트워크 연결을 기다리는 중") }
                    UploadState.Draft -> Button(onClick = { onUploadStateChange(UploadState.Uploading) }, enabled = canEdit && consent, modifier = Modifier.fillMaxWidth()) { Text("안전하게 게시하기") }
                }
            }
        }
    }
}
