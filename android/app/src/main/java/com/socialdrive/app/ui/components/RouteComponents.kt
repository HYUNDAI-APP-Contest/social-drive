package com.socialdrive.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.ui.theme.LocalRouteMemoryColors

@Composable
fun RouteRibbon(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
    showMoments: Boolean = false,
) {
    val routeColors = LocalRouteMemoryColors.current
    Canvas(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "개인 위치가 제거된 데모 드라이브 경로"
        },
    ) {
        if (points.size < 2) return@Canvas
        val path = Path().apply {
            moveTo(points.first().horizontal * size.width, points.first().vertical * size.height)
            points.drop(1).forEach { lineTo(it.horizontal * size.width, it.vertical * size.height) }
        }
        drawPath(path, routeColors.route.copy(alpha = .18f), style = Stroke(18.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, routeColors.route, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(routeColors.highlight, 9.dp.toPx(), Offset(points.first().horizontal * size.width, points.first().vertical * size.height))
        drawCircle(routeColors.highlight, 9.dp.toPx(), Offset(points.last().horizontal * size.width, points.last().vertical * size.height))
        if (showMoments) points.drop(1).dropLast(1).filterIndexed { index, _ -> index % 2 == 0 }.forEach {
            drawCircle(routeColors.moment, 7.dp.toPx(), Offset(it.horizontal * size.width, it.vertical * size.height))
        }
    }
}

@Composable
fun DriveSummaryCard(drive: DriveSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalRouteMemoryColors.current
    Card(
        modifier = modifier
            .heightIn(min = 232.dp)
            .clickable(role = Role.Button, onClickLabel = "${drive.title} 상세 보기", onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(colors.brand, MaterialTheme.colorScheme.surfaceVariant))),
        ) {
            RouteRibbon(drive.route, Modifier.fillMaxSize().padding(18.dp))
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.colorScheme.surface)))
                    .padding(20.dp),
            ) {
                Text(drive.date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(drive.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(drive.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Metric(Icons.Rounded.Route, "${drive.distance} · ${drive.duration}")
                    Metric(Icons.Rounded.Star, "하이라이트 ${drive.highlights}")
                }
            }
            RouteSourceBadge(drive.routeSource, Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DemoDataBadge(modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
        Text("데모 데이터", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RouteSourceBadge(source: RouteSource, modifier: Modifier = Modifier) {
    val label = when (source) {
        RouteSource.DemoPreset -> "데모 데이터"
        is RouteSource.LiveUnavailable -> "실시간 경로 사용 불가"
        else -> "실시간 경로"
    }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun IntegrationStatusChip(status: CapabilityStatus, modifier: Modifier = Modifier) {
    val label = when (status.source) {
        IntegrationSource.Actual -> "ACTUAL · ${status.label}"
        IntegrationSource.Demo -> "DEMO · ${status.label}"
        IntegrationSource.Unavailable -> "UNAVAILABLE"
    }
    Surface(modifier.clip(CircleShape), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SafetyRestrictionBanner(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
fun UploadStateChip(state: UploadState, modifier: Modifier = Modifier) {
    val label = when (state) {
        UploadState.Draft -> "초안 저장됨"
        UploadState.Queued -> "오프라인 대기"
        UploadState.Uploading -> "업로드 중"
        UploadState.Failed -> "재시도 필요"
        UploadState.Published -> "게시 완료"
    }
    Surface(modifier.clip(CircleShape), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}
