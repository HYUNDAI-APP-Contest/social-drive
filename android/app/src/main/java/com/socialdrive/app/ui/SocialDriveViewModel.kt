package com.socialdrive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.socialdrive.app.SocialDriveApplication
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.EnqueueResult
import com.socialdrive.app.data.local.RecordingEntity
import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.domain.UploadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SocialDriveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SocialDriveApplication

    val activeRecording: StateFlow<RecordingEntity?> = app.recordingRepository.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestDraft: StateFlow<DraftSnapshot?> = app.draftRepository.drafts
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uploadState: StateFlow<UploadState> = latestDraft
        .flatMapLatest { draft -> draft?.let { app.publishQueueRepository.observeState(it.id) } ?: flowOf(UploadState.Draft) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UploadState.Draft)

    suspend fun startRecording() = app.recordingRepository.start()

    suspend fun addHighlight() {
        activeRecording.value?.let { app.recordingRepository.addHighlight(it.id) }
    }

    suspend fun finishActiveRecording(): DraftSnapshot? = app.recordingRepository.finishActiveAsDraft()

    suspend fun updateDraftCopy(title: String, description: String): Boolean =
        latestDraft.value?.let { app.draftRepository.updateCopy(it.id, title, description) } ?: false

    suspend fun enqueuePublish(motionState: MotionState): EnqueueResult =
        latestDraft.value?.let { app.publishScheduler.enqueue(it.id, motionState) } ?: EnqueueResult.MissingDraft
}
