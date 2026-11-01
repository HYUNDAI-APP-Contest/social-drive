package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.ui.components.DriveSummaryCard
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@Composable
fun HomeScreen(
    state: ContentState<List<DriveSummary>>,
    blockedMessage: String?,
    onDetail: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("오늘의 드라이브", style = MaterialTheme.typography.headlineLarge)
                Text("달린 만큼, 더 특별한 순간이 됩니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onRecord, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
                Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                Text(" 기록 시작")
            }
            IconButton(onClick = onSettings, modifier = Modifier.padding(start = 12.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = "설정 열기")
            }
        }
        if (blockedMessage != null) SafetyRestrictionBanner(blockedMessage, Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("전체", "바다", "산", "도심", "즐겨찾기").forEachIndexed { index, label ->
                FilterChip(selected = index == 0, onClick = {}, label = { Text(label) })
            }
        }
        when (state) {
            ContentState.Loading -> MessageState("드라이브를 불러오는 중이에요")
            ContentState.Empty -> MessageState("아직 저장된 드라이브가 없어요", "첫 드라이브 기록하기", onRecord)
            is ContentState.Error -> MessageState(state.message, "다시 시도", onRetry)
            is ContentState.PermissionDenied -> MessageState("${state.capability} 권한 없이도 데모 피드를 둘러볼 수 있어요")
            is ContentState.Retry -> MessageState(state.message, "다시 시도", onRetry)
            is ContentState.Content -> DriveGrid(state.value, onDetail)
            is ContentState.Offline -> Column(Modifier.fillMaxSize()) {
                SafetyRestrictionBanner("오프라인입니다. 저장된 드라이브를 보여드려요.", Modifier.padding(horizontal = 28.dp))
                DriveGrid(state.cached, onDetail)
            }
        }
    }
}

@Composable
private fun DriveGrid(drives: List<DriveSummary>, onDetail: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(360.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(drives, key = { it.id }) { drive -> DriveSummaryCard(drive, { onDetail(drive.id) }) }
    }
}

@Composable
private fun MessageState(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleLarge)
        if (action != null) OutlinedButton(onClick = onAction, Modifier.padding(top = 16.dp)) { Text(action) }
    }
}
