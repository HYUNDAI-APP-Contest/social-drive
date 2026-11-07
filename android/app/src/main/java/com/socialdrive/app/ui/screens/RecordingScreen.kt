package com.socialdrive.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.CapabilityState
import com.socialdrive.app.domain.RecordingSnapshot
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.ui.components.RouteMemoryArtwork
import com.socialdrive.app.ui.components.RouteSourceBadge
import com.socialdrive.app.ui.components.memoryPaletteFor
import com.socialdrive.app.ui.theme.LocalRouteMemoryColors

@Composable
fun RecordingScreen(snapshot: RecordingSnapshot, onHighlight: () -> Unit, onRequestStop: () -> Unit) {
    val colors = LocalRouteMemoryColors.current
    val haptics = LocalHapticFeedback.current
    val largeText = LocalDensity.current.fontScale >= 1.2f
    var capturedFeedbackCount by rememberSaveable { mutableStateOf<Int?>(null) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(colors.brand, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant)),
        ),
    ) {
        RouteMemoryArtwork(
            points = snapshot.route,
            palette = memoryPaletteFor(snapshot.storyId),
            momentCount = snapshot.highlights,
            selectedMomentIndex = (snapshot.highlights - 1).takeIf { it >= 0 },
            routeSource = snapshot.routeSource,
            modifier = Modifier.fillMaxSize().padding(start = 350.dp, top = 92.dp, end = 20.dp, bottom = 104.dp),
        )
        capturedFeedbackCount?.let { count ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(start = 320.dp, top = 104.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    "순간 $count 저장됨 · 주차 후 이야기에서 확인",
                    Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Column(Modifier.fillMaxSize().padding(28.dp)) {
            if (largeText) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecordingBadge(colors.highlight)
                        RouteSourceBadge(snapshot.routeSource, Modifier.padding(start = 12.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        StatusIcon(Icons.Rounded.GpsFixed, "실시간 위치", snapshot.gps)
                        StatusIcon(Icons.Rounded.DirectionsCar, "차량", snapshot.vehicle)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RecordingBadge(colors.highlight)
                    RouteSourceBadge(snapshot.routeSource, Modifier.padding(start = 12.dp))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        StatusIcon(Icons.Rounded.GpsFixed, "실시간 위치", snapshot.gps)
                        StatusIcon(Icons.Rounded.DirectionsCar, "차량", snapshot.vehicle)
                    }
                }
            }
            Surface(
                modifier = Modifier.weight(1f).padding(vertical = 20.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
            ) {
                Column(
                    Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(recordingRouteTitle(snapshot.routeSource), style = MaterialTheme.typography.titleLarge)
                    Text("경과 시간", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(snapshot.elapsed, style = MaterialTheme.typography.displayLarge)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                        RecordingMetric("주행 거리", snapshot.distance)
                        RecordingMetric("하이라이트", "${snapshot.highlights}개")
                    }
                    Text(
                        recordingRoutePrivacyLabel(snapshot.routeSource),
                        Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        capturedFeedbackCount = snapshot.highlights + 1
                        onHighlight()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.highlight, contentColor = MaterialTheme.colorScheme.background),
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, Modifier.size(28.dp))
                    Text(" 방금 좋았어 (${snapshot.highlights})", style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = onRequestStop,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, Modifier.size(28.dp))
                    Text(" 기록 종료", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

internal fun recordingRouteTitle(source: com.socialdrive.app.domain.RouteSource): String =
    if (source == com.socialdrive.app.domain.RouteSource.LiveAvailable) "실시간 경로를 안전하게 기록하고 있어요"
    else "데모 경로를 기록하고 있어요"

internal fun recordingRoutePrivacyLabel(source: com.socialdrive.app.domain.RouteSource): String =
    if (source == com.socialdrive.app.domain.RouteSource.LiveAvailable) "원본 위치는 저장하지 않고 경로 형태만 보존합니다."
    else "정밀 위치는 수집하지 않습니다."

@Composable
private fun RecordingBadge(highlight: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = highlight.copy(alpha = .16f)) {
        Text(
            "●  기록 중",
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            color = highlight,
        )
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
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("초안으로 저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("계속 기록")
            }
        },
    )
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, state: CapabilityState) {
    val status = when (state) {
        CapabilityState.Available -> "연결됨"
        CapabilityState.Unavailable -> if (label == "실시간 위치") "미사용" else "미확인"
        CapabilityState.PermissionDenied -> "권한 필요"
    }
    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(" $label $status", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RecordingMetric(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.displaySmall)
    }
}
