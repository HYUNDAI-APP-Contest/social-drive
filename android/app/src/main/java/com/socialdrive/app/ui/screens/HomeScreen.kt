package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.ContentState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.ui.components.ContentStateKind
import com.socialdrive.app.ui.components.ContentStatePanel
import com.socialdrive.app.ui.components.DriveSummaryCard
import com.socialdrive.app.ui.components.DemoDataBadge
import com.socialdrive.app.ui.components.IntegrationStatusChip
import com.socialdrive.app.ui.components.OfflineCacheBanner
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@Composable
fun HomeScreen(
    state: ContentState<List<DriveSummary>>,
    motion: MotionState,
    vehicleStatus: CapabilityStatus? = null,
    blockedMessage: String?,
    onDetail: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    if (motion != MotionState.Parked) {
        DrivingHome(motion, vehicleStatus, blockedMessage, onRecord)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("오늘의 드라이브", style = MaterialTheme.typography.headlineLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("좋았던 길과 순간을 안전하게 남겨요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DemoDataBadge()
                }
            }
            Button(
                onClick = onRecord,
                modifier = Modifier.heightIn(min = 64.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                Text(" 기록 시작")
            }
            if (vehicleStatus != null) IntegrationStatusChip(vehicleStatus, Modifier.padding(start = 12.dp), "차량 상태")
            IconButton(onClick = onSettings, modifier = Modifier.padding(start = 12.dp).size(48.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = "설정 열기")
            }
        }
        if (blockedMessage != null) SafetyRestrictionBanner(blockedMessage, Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("표시 범위", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "전체 기록",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text("카드 태그 · 바다 · 산 · 도심", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Give state content only the remaining viewport. This keeps the header and
        // primary action stable when a state (notably offline cache) fills its area.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (state) {
                ContentState.Loading -> ContentStatePanel(
                    ContentStateKind.Loading,
                    "드라이브를 불러오고 있어요",
                    "저장된 기록을 확인하는 중입니다.",
                )
                ContentState.Empty -> ContentStatePanel(
                    ContentStateKind.Empty,
                    "아직 남긴 드라이브가 없어요",
                    "주행 중에는 한 번의 터치로 좋은 순간을 남길 수 있어요.",
                    actionLabel = "첫 드라이브 기록하기",
                    onAction = onRecord,
                )
                is ContentState.Error -> ContentStatePanel(
                    ContentStateKind.Error,
                    "드라이브를 불러오지 못했어요",
                    "기기에 저장된 초안과 기록은 삭제되지 않았습니다.",
                    actionLabel = "다시 시도",
                    onAction = onRetry,
                )
                is ContentState.PermissionDenied -> ContentStatePanel(
                    ContentStateKind.Empty,
                    "${state.capability} 없이 데모를 둘러볼 수 있어요",
                    "현재 데모 경로만 사용하며 실제 위치는 수집하지 않습니다.",
                )
                is ContentState.Retry -> ContentStatePanel(
                    ContentStateKind.Error,
                    "연결이 불안정해요",
                    "기기에 저장된 기록은 그대로 보존됩니다.",
                    actionLabel = "다시 시도",
                    onAction = onRetry,
                )
                is ContentState.Content -> DriveGrid(state.value, onDetail)
                is ContentState.Offline -> if (state.cached.isEmpty()) {
                    ContentStatePanel(
                        ContentStateKind.Offline,
                        "연결이 필요해요",
                        "기기에 저장된 드라이브가 없습니다.",
                        actionLabel = "다시 시도",
                        onAction = onRetry,
                    )
                } else Column(Modifier.fillMaxSize()) {
                    OfflineCacheBanner(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    DriveGrid(state.cached, onDetail)
                }
            }
        }
    }
}
@Composable
private fun DrivingHome(
    motion: MotionState,
    vehicleStatus: CapabilityStatus?,
    blockedMessage: String?,
    onRecord: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                if (motion == MotionState.Driving) "주행에 집중하세요" else "차량 상태를 확인하고 있어요",
                style = MaterialTheme.typography.headlineLarge,
            )
            if (vehicleStatus != null) IntegrationStatusChip(vehicleStatus, contextLabel = "차량 상태")
        }
        SafetyRestrictionBanner(
            blockedMessage ?: if (motion == MotionState.Driving) "피드와 설정은 주차 후 이용할 수 있어요." else "주차 상태가 확인될 때까지 탐색 기능을 잠갔어요.",
            Modifier.padding(vertical = 20.dp),
        )
        Button(onClick = onRecord, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp)) {
            Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
            Text(" 안전 기록 화면 열기", style = MaterialTheme.typography.titleLarge)
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
