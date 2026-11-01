package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import com.socialdrive.app.ui.components.SafetyRestrictionBanner
import com.socialdrive.app.ui.components.UploadStateChip

@Composable
fun PublishScreen(canEdit: Boolean, onBack: () -> Unit, onPublished: () -> Unit) {
    var title by remember { mutableStateOf("빛나는 해안도로의 저녁") }
    var description by remember { mutableStateOf("바다를 따라 만난 좋은 순간을 나눠요.") }
    var consent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column(Modifier.weight(1f)) {
                Text("드라이브 게시", style = MaterialTheme.typography.headlineMedium)
                Text("대표 이미지 → 이야기 → 공개 범위 → 동의", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UploadStateChip(UploadState.Draft)
        }
        if (!canEdit) SafetyRestrictionBanner("주차 상태를 확인한 뒤 게시물을 편집할 수 있어요.", Modifier.padding(vertical = 12.dp))
        Row(
            Modifier.fillMaxSize().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("1  대표 장면", style = MaterialTheme.typography.titleLarge)
                androidx.compose.material3.Card(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DEMO ROUTE ARTWORK", color = MaterialTheme.colorScheme.secondary)
                        Text("실제 위치와 사진은 포함되지 않습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("2  나의 이야기", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(title, { title = it.take(40) }, Modifier.fillMaxWidth(), enabled = canEdit, label = { Text("제목") }, singleLine = true)
                OutlinedTextField(description, { description = it.take(120) }, Modifier.fillMaxWidth(), enabled = canEdit, label = { Text("짧은 설명") }, minLines = 3)
                Text("3  공개 범위 · 전체 공개", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = canEdit)
                    Text("게시할 데모 경로와 하이라이트 정보를 확인했습니다.")
                }
                Text("정밀 위치와 원시 차량 상태는 업로드하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPublished, enabled = canEdit && consent, modifier = Modifier.fillMaxWidth()) { Text("안전하게 게시하기") }
            }
        }
    }
}
