package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.NavigationState
import com.socialdrive.app.ui.components.IntegrationStatusChip
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.components.RouteRibbon
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    drive: DriveSummary,
    navigationCapability: CapabilityStatus,
    navigationState: NavigationState,
    onRequestNavigation: (NavigationRequest) -> Unit,
    onBack: () -> Unit,
    onPublish: () -> Unit,
) {
    var showCapabilitySheet by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(navigationState) {
        feedback = when (navigationState) {
            is NavigationState.Requesting -> "차량 내비게이션에 경로를 요청하고 있어요."
            is NavigationState.Active -> "차량 내비게이션에서 경로를 시작했어요."
            is NavigationState.Failed -> navigationState.userMessage
            is NavigationState.Idle -> feedback
        }
    }
    if (showCapabilitySheet) {
        ModalBottomSheet(onDismissRequest = { showCapabilitySheet = false }) {
            Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NaviHelper 안내", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (drive.navigationRequest == null) {
                        "이 데모 기록에는 실제 좌표가 포함되지 않아 차량 내비게이션으로 보내지 않아요."
                    } else {
                        "차량 내비게이션 연결 상태를 확인해 주세요."
                    },
                )
                Text("서버가 차량을 직접 제어하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showCapabilitySheet = false }) { Text("확인") }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column(Modifier.weight(1f)) {
                Text(drive.title, style = MaterialTheme.typography.headlineMedium)
                Text("${drive.date} · ${drive.duration} · ${drive.distance}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RouteSourceBadge(drive.routeSource)
            IntegrationStatusChip(navigationCapability, Modifier.padding(start = 8.dp))
            IconButton(onClick = { feedback = "데모에서는 개인정보 보호를 위해 외부 공유를 실행하지 않아요." }) {
                Icon(Icons.Rounded.Share, contentDescription = "드라이브 공유 안내")
            }
        }
        if (feedback != null) SafetyRestrictionBanner(requireNotNull(feedback), Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
            Card(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
                RouteRibbon(drive.route, Modifier.fillMaxSize().padding(30.dp), showMoments = true)
            }
            Button(
                onClick = {
                    val request = drive.navigationRequest
                    if (navigationCapability.capability == CapabilityState.Available && request != null) {
                        feedback = "차량 내비게이션에 경로를 요청하고 있어요."
                        onRequestNavigation(request)
                    } else showCapabilitySheet = true
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                enabled = navigationState !is NavigationState.Requesting,
            ) {
                Icon(Icons.Rounded.Navigation, contentDescription = null)
                Text(if (navigationState is NavigationState.Requesting) " 요청 중…" else " 이 길 따라가기")
            }
        }
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("하이라이트 ${drive.moments.size}", style = MaterialTheme.typography.titleLarge)
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(drive.moments, key = { it.id }) { moment ->
                    Card(Modifier.fillParentMaxWidth(.28f).height(126.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(moment.time, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                            Text(moment.title, style = MaterialTheme.typography.titleMedium)
                            Text(moment.description, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = onPublish, Modifier.align(Alignment.End).padding(top = 12.dp)) { Text("이 드라이브 게시하기") }
        }
    }
}
