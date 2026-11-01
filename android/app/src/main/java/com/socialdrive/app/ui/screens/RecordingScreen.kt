package com.socialdrive.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.ui.components.RouteRibbon
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.theme.LocalRouteMemoryColors

@Composable
fun RecordingScreen(snapshot: RecordingSnapshot, onHighlight: () -> Unit, onRequestStop: () -> Unit) {
    val colors = LocalRouteMemoryColors.current
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(colors.brand, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant)),
        ),
    ) {
        RouteRibbon(
            points = listOf(RoutePoint(.04f, .82f), RoutePoint(.3f, .7f), RoutePoint(.5f, .38f), RoutePoint(.76f, .48f), RoutePoint(.96f, .2f)),
            modifier = Modifier.fillMaxSize().padding(start = 360.dp, top = 72.dp, bottom = 72.dp),
            showMoments = true,
        )
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = colors.highlight.copy(alpha = .16f)) {
                    Text("●  기록 중", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium, color = colors.highlight)
                }
                RouteSourceBadge(snapshot.routeSource, Modifier.padding(start = 12.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    StatusIcon(Icons.Rounded.GpsFixed, "GPS", snapshot.gps)
                    StatusIcon(Icons.Rounded.DirectionsCar, "차량", snapshot.vehicle)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("주행 시간", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(snapshot.elapsed, style = MaterialTheme.typography.displayLarge)
                Text("주행 거리", Modifier.padding(top = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(snapshot.distance, style = MaterialTheme.typography.displayMedium)
                Text("정밀 위치를 공유하지 않는 데모 기록", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onHighlight() },
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.highlight, contentColor = MaterialTheme.colorScheme.background),
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, Modifier.size(28.dp))
                    Text(" 방금 좋았어 (${snapshot.highlights})", style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onRequestStop,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, Modifier.size(28.dp))
                    Text(" 기록 종료", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
fun RecordingStopDialog(motion: MotionState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기록을 종료할까요?") },
        text = {
            Text(
                if (motion == MotionState.Parked) "기록을 안전한 초안으로 저장한 뒤 게시 화면으로 이동합니다."
                else "기록은 초안으로 보존됩니다. 게시와 편집은 주차 상태가 확인된 후에만 가능합니다.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("초안으로 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("계속 기록") } },
    )
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, state: CapabilityState) {
    val status = when (state) {
        CapabilityState.Available -> "정상"
        CapabilityState.Unavailable -> "데모"
        CapabilityState.PermissionDenied -> "권한 필요"
    }
    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(" $label $status", style = MaterialTheme.typography.labelLarge)
    }
}
