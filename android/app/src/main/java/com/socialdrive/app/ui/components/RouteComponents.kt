package com.socialdrive.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.socialdrive.app.R
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.EphemeralCoverImage
import com.socialdrive.app.domain.CapabilityStatus
import com.socialdrive.app.domain.IntegrationSource
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource
import com.socialdrive.app.domain.UploadState
import com.socialdrive.app.ui.theme.LocalRouteMemoryColors
import com.socialdrive.app.ui.theme.MemoryArtworkTopScrim
import com.socialdrive.app.ui.theme.MemoryArtworkMiddleScrim
import com.socialdrive.app.ui.theme.MemoryArtworkBottomScrim
import com.socialdrive.app.ui.theme.MemoryCardMiddleScrim
import com.socialdrive.app.ui.theme.MemoryCardBottomScrim
import com.socialdrive.app.ui.theme.MemoryCardDate
import com.socialdrive.app.ui.theme.MemoryCardSummary
import com.socialdrive.app.ui.theme.MemoryCardMetric

enum class MemoryPalette { Coast, Mountain, City }

internal fun memoryPaletteFor(id: String): MemoryPalette = when {
    id.contains("mountain") -> MemoryPalette.Mountain
    id.contains("city") -> MemoryPalette.City
    else -> MemoryPalette.Coast
}

private fun memoryArtworkRes(palette: MemoryPalette): Int = when (palette) {
    MemoryPalette.Coast -> R.drawable.drive_coast_night_v1
    MemoryPalette.Mountain -> R.drawable.drive_mountain_autumn_v1
    MemoryPalette.City -> R.drawable.drive_city_night_v1
}

/**
 * Privacy-safe memory artwork. Generic editorial backdrops contain no user data, while
 * the route overlay is derived only from normalized demo points (never raw coordinates).
 */
@Composable
fun RouteMemoryArtwork(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
    palette: MemoryPalette = MemoryPalette.Coast,
    momentCount: Int = 0,
    selectedMomentIndex: Int? = null,
    compact: Boolean = false,
    routeSource: RouteSource = RouteSource.DemoPreset,
    coverImage: EphemeralCoverImage? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val routeColors = LocalRouteMemoryColors.current
    val gradient = when (palette) {
        MemoryPalette.Coast -> listOf(routeColors.brand, scheme.secondaryContainer, scheme.surfaceVariant)
        MemoryPalette.Mountain -> listOf(scheme.tertiaryContainer, routeColors.brand, scheme.surfaceVariant)
        MemoryPalette.City -> listOf(routeColors.brand, scheme.surfaceVariant, scheme.secondaryContainer)
    }
    Box(modifier.background(Brush.linearGradient(gradient))) {
        val decodedCover = remember(coverImage) { coverImage?.decodeBounded() }
        if (decodedCover != null) {
            Image(
                bitmap = decodedCover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(memoryArtworkRes(palette)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(MemoryArtworkTopScrim, MemoryArtworkMiddleScrim, MemoryArtworkBottomScrim),
                    ),
                ),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {
                    contentDescription = routeArtworkDescription(routeSource, momentCount, points.size)
                },
        ) {
            when (palette) {
                MemoryPalette.Coast -> {
                    drawCircle(scheme.secondary.copy(alpha = .16f), radius = size.minDimension * .34f, center = Offset(size.width * .82f, size.height * .12f))
                    repeat(3) { index ->
                        val y = size.height * (.7f + index * .07f)
                        drawLine(scheme.onSurface.copy(alpha = .08f), Offset(0f, y), Offset(size.width, y - size.height * .06f), 2.dp.toPx())
                    }
                }
                MemoryPalette.Mountain -> {
                    val ridge = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width * .2f, size.height * .38f)
                        lineTo(size.width * .38f, size.height * .72f)
                        lineTo(size.width * .63f, size.height * .24f)
                        lineTo(size.width, size.height * .7f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(ridge, scheme.onSurface.copy(alpha = .09f))
                }
                MemoryPalette.City -> {
                    repeat(9) { index ->
                        val width = size.width / 13f
                        val height = size.height * (.12f + (index % 4) * .055f)
                        drawRect(
                            scheme.onSurface.copy(alpha = .09f),
                            topLeft = Offset(size.width * (.03f + index * .11f), size.height - height),
                            size = Size(width, height),
                        )
                    }
                }
            }
            drawMemoryRoute(
                points = points,
                route = routeColors.route,
                endpoint = routeColors.highlight,
                moment = routeColors.moment,
                momentCount = momentCount,
                selectedMomentIndex = selectedMomentIndex,
                compact = compact,
            )
        }
    }
}

internal fun routeArtworkDescription(source: RouteSource, momentCount: Int, pointCount: Int = 2): String {
    val route = when {
        source == RouteSource.LiveAvailable && pointCount < 2 -> "실시간 경로 수집 중인 배경"
        source == RouteSource.LiveAvailable -> "원본 위치가 제거된 실시간 경로"
        source == RouteSource.CloudPublished -> "서버에 공개된 정규화 경로"
        else -> "개인 위치가 제거된 데모 경로"
    }
    return "$route · 하이라이트 ${momentCount.coerceAtLeast(0)}개"
}

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
        drawMemoryRoute(
            points,
            routeColors.route,
            routeColors.highlight,
            routeColors.moment,
            momentCount = if (showMoments) points.size.coerceAtMost(4) else 0,
            selectedMomentIndex = null,
            compact = false,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMemoryRoute(
    points: List<RoutePoint>,
    route: Color,
    endpoint: Color,
    moment: Color,
    momentCount: Int,
    selectedMomentIndex: Int?,
    compact: Boolean,
) {
    if (points.size < 2) return
    fun RoutePoint.offset() = Offset(horizontal.coerceIn(0f, 1f) * size.width, vertical.coerceIn(0f, 1f) * size.height)
    val path = Path().apply {
        moveTo(points.first().offset().x, points.first().offset().y)
        points.drop(1).forEach { point -> point.offset().let { lineTo(it.x, it.y) } }
    }
    val routeWidth = if (compact) 4.dp.toPx() else 6.dp.toPx()
    drawPath(path, route.copy(alpha = .2f), style = Stroke(routeWidth * 3f, cap = StrokeCap.Round))
    drawPath(path, route, style = Stroke(routeWidth, cap = StrokeCap.Round))
    val endpointRadius = if (compact) 7.dp.toPx() else 9.dp.toPx()
    drawCircle(endpoint, endpointRadius, points.first().offset())
    drawCircle(endpoint, endpointRadius, points.last().offset())
    val count = momentCount.coerceIn(0, (points.size - 2).coerceAtLeast(0))
    repeat(count) { index ->
        val pointIndex = ((index + 1) * (points.lastIndex.toFloat() / (count + 1))).toInt().coerceIn(1, points.lastIndex - 1)
        val center = points[pointIndex].offset()
        val selected = selectedMomentIndex == index
        if (selected) drawCircle(moment.copy(alpha = .25f), 16.dp.toPx(), center)
        drawCircle(if (selected) endpoint else moment, (if (selected) 10 else 7).dp.toPx(), center)
    }
}

@Composable
fun DriveSummaryCard(
    drive: DriveSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    featured: Boolean = false,
    cardHeight: Dp? = null,
) {
    val largeText = LocalDensity.current.fontScale >= 1.2f
    Card(
        modifier = modifier
            // A concrete height lets the artwork Box own the complete grid item.
            // heightIn(min) left fillMaxSize() measuring only the text content,
            // which produced a visually empty lower half in the judge viewport.
            .height(cardHeight ?: if (featured) 344.dp else 276.dp)
            .clickable(role = Role.Button, onClickLabel = "${drive.title} 상세 보기", onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxSize()) {
            RouteMemoryArtwork(
                points = drive.route,
                palette = memoryPaletteFor(drive.id),
                momentCount = drive.highlights,
                compact = true,
                routeSource = drive.routeSource,
                coverImage = drive.coverImage,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                MemoryCardMiddleScrim,
                                MemoryCardBottomScrim,
                            ),
                        ),
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                Text(drive.date, style = MaterialTheme.typography.bodyMedium, color = MemoryCardDate)
                Text(
                    drive.title,
                    style = if (featured) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(drive.summary, style = MaterialTheme.typography.bodyMedium, color = MemoryCardSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                if (largeText) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Metric(Icons.Rounded.Route, "${drive.distance} · ${drive.duration}")
                        Metric(Icons.Rounded.Star, "하이라이트 ${drive.highlights}")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Metric(Icons.Rounded.Route, "${drive.distance} · ${drive.duration}")
                        Metric(Icons.Rounded.Star, "하이라이트 ${drive.highlights}")
                    }
                }
            }
            RouteSourceBadge(drive.routeSource, Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
    }
}

private fun EphemeralCoverImage.decodeBounded(): android.graphics.Bitmap? {
    val bytes = copyBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth > MAX_COVER_DIMENSION || bounds.outHeight > MAX_COVER_DIMENSION ||
        bounds.outWidth.toLong() * bounds.outHeight > MAX_COVER_PIXELS
    ) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = generateSequence(1) { it * 2 }
            .first { bounds.outWidth / it <= TARGET_COVER_DIMENSION && bounds.outHeight / it <= TARGET_COVER_DIMENSION }
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private const val MAX_COVER_DIMENSION = 8_192
private const val MAX_COVER_PIXELS = 16_777_216L
private const val TARGET_COVER_DIMENSION = 2_048

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(text, style = MaterialTheme.typography.labelLarge, color = MemoryCardMetric)
    }
}

@Composable
fun DemoDataBadge(modifier: Modifier = Modifier, label: String = "데모 데이터") {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RouteSourceBadge(source: RouteSource, modifier: Modifier = Modifier) {
    val label = when (source) {
        RouteSource.DemoPreset -> "데모 데이터"
        is RouteSource.LiveUnavailable -> "실시간 경로 사용 불가"
        RouteSource.LiveAvailable -> "실시간 경로"
        RouteSource.CloudPublished -> "AWS 공개 경로"
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
fun IntegrationStatusChip(
    status: CapabilityStatus,
    modifier: Modifier = Modifier,
    contextLabel: String = "연동 상태",
) {
    val label = integrationStatusLabel(status, contextLabel)
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

internal fun integrationStatusLabel(status: CapabilityStatus, contextLabel: String): String = when (status.source) {
    IntegrationSource.Actual -> when {
        contextLabel == "AWS 클라우드" && status.label.startsWith("AWS · ") -> status.label
        status.capability == com.socialdrive.app.domain.CapabilityState.Available -> "PLEOS 연결됨 · $contextLabel"
        status.label.contains("주차 신호") -> "PLEOS 연결됨 · 주차 신호 미확인"
        status.label.contains("연결 안 됨") -> "$contextLabel 연결 미확인"
        else -> "$contextLabel 미확인"
    }
    IntegrationSource.Demo -> "데모 $contextLabel"
    IntegrationSource.Unavailable -> "$contextLabel 사용 불가"
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
        UploadState.Rejected -> "게시 불가"
        UploadState.NeedsConflictReview -> "버전 검토 필요"
        UploadState.NeedsConsentRenewal -> "경로 동의 갱신 필요"
        UploadState.Published -> "게시 완료"
    }
    Surface(modifier.clip(CircleShape), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}
