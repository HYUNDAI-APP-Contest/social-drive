package com.socialdrive.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class ContentStateKind { Loading, Empty, Offline, Error }

@Composable
fun ContentStatePanel(
    kind: ContentStateKind,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val icon = when (kind) {
        ContentStateKind.Loading -> Icons.Rounded.HourglassTop
        ContentStateKind.Empty -> Icons.Rounded.Route
        ContentStateKind.Offline -> Icons.Rounded.CloudOff
        ContentStateKind.Error -> Icons.Rounded.ErrorOutline
    }
    Column(
        modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(20.dp).size(36.dp),
                tint = stateTint(kind),
            )
        }
        Text(title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.headlineMedium)
        Text(
            description,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 20.dp).heightIn(min = 48.dp),
            ) { Text(actionLabel) }
        }
    }
}

@Composable
fun OfflineCacheBanner(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column {
                Text("오프라인 · 저장된 기록 표시 중", style = MaterialTheme.typography.titleMedium)
                Text("연결되면 최신 목록을 다시 확인합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun stateTint(kind: ContentStateKind) = when (kind) {
    ContentStateKind.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.secondary
}
