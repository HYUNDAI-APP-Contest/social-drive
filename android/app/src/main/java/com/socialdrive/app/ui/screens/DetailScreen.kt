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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.ui.components.DemoDataBadge
import com.socialdrive.app.ui.components.RouteRibbon

@Composable
fun DetailScreen(drive: DriveSummary, onBack: () -> Unit, onPublish: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column(Modifier.weight(1f)) {
                Text(drive.title, style = MaterialTheme.typography.headlineMedium)
                Text("${drive.date} · ${drive.duration} · ${drive.distance}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DemoDataBadge()
            IconButton(onClick = {}) { Icon(Icons.Rounded.Share, contentDescription = "드라이브 공유") }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
            Card(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
                RouteRibbon(drive.route, Modifier.fillMaxSize().padding(30.dp), showMoments = true)
            }
            Button(onClick = {}, Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
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
