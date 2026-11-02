package com.socialdrive.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.ConflictReviewPolicy
import com.socialdrive.app.domain.ConflictReviewUiState
import com.socialdrive.app.domain.ConflictUiPhase
import com.socialdrive.app.domain.MAX_CONFLICT_REPLAYS
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.ui.components.SafetyRestrictionBanner

@Composable
fun ConflictReviewScreen(
    state: ConflictReviewUiState,
    motion: MotionState,
    onFetch: () -> Unit,
    onAdopt: () -> Unit,
    onKeepLocal: () -> Unit,
    onBack: () -> Unit,
) {
    val parked = motion == MotionState.Parked
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로가기") }
            Column(Modifier.weight(1f)) {
                Text("서버의 새 버전이 있어요", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "자동으로 덮어쓰지 않았습니다. 초안은 이 기기에 그대로 있습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!parked) {
            SafetyRestrictionBanner("주차 상태를 확인한 뒤 충돌을 검토할 수 있어요.")
            return@Column
        }
        when (state.phase) {
            ConflictUiPhase.Fetching -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("최신 버전을 한 번 확인하고 있어요…")
            }
            ConflictUiPhase.SnapshotReady -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("서버 초안 · 버전 ${state.latestVersion}", style = MaterialTheme.typography.titleMedium)
                    Text(state.latestTitle.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(state.latestSummary.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("재게시 ${state.replayCount}/$MAX_CONFLICT_REPLAYS", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ConflictUiPhase.FetchFailed -> Text(
                ConflictReviewPolicy.SAFE_FETCH_FAILURE,
                color = MaterialTheme.colorScheme.error,
            )
            ConflictUiPhase.KeptLocal -> Text("로컬 초안을 유지했어요. 자동 게시나 네트워크 요청은 하지 않습니다.")
            ConflictUiPhase.ReplayQueued -> Text("선택을 저장했어요. 게시 단계만 안전하게 다시 시도합니다.")
            ConflictUiPhase.Resolved -> Text("게시 충돌 검토를 완료했어요.")
            ConflictUiPhase.Hidden -> Text("검토할 충돌이 없습니다.")
            ConflictUiPhase.Unreviewable -> Text(ConflictReviewPolicy.SAFE_FETCH_FAILURE)
            ConflictUiPhase.Detected -> Unit
        }
        if (state.canFetch) {
            Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) { Text("최신 버전 확인") }
        }
        if (state.canAdopt) {
            Button(onClick = onAdopt, modifier = Modifier.fillMaxWidth()) { Text("최신 버전을 기준으로 다시 게시") }
        }
        if (state.phase in setOf(ConflictUiPhase.Detected, ConflictUiPhase.FetchFailed, ConflictUiPhase.SnapshotReady)) {
            OutlinedButton(onClick = onKeepLocal, modifier = Modifier.fillMaxWidth()) { Text("로컬 초안 유지") }
        }
    }
}
