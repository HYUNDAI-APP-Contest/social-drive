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
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.ui.components.DemoDataBadge
import com.socialdrive.app.ui.components.RouteRibbon
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    drive: DriveSummary,
    navigationCapability: CapabilityState,
    onBack: () -> Unit,
    onPublish: () -> Unit,
) {
    var showCapabilitySheet by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    if (showCapabilitySheet) {
        ModalBottomSheet(onDismissRequest = { showCapabilitySheet = false }) {
            Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NaviHelper 연결 준비 중", style = MaterialTheme.typography.headlineMedium)
                Text("현재는 공모전용 데모 경로입니다. 실제 차량 capability 승인이 완료되면 목적지와 경유지만 안전하게 전달합니다.")
                Text("서버가 차량을 직접 제어하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showCapabilitySheet = false }) { Text("확인") }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column(Modifier.weight(1f)) {
                Text(drive.title, style = MaterialTheme.typography.headlineMedium)
                Text("${drive.date} · ${drive.duration} · ${drive.distance}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DemoDataBadge()
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
                    if (navigationCapability == CapabilityState.Available) feedback = "안전 확인 후 NaviHelper로 경로를 전달합니다."
                    else showCapabilitySheet = true
                },
                Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(Icons.Rounded.Navigation, contentDescription = null)
                Text(" 이 길 따라가기")
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
