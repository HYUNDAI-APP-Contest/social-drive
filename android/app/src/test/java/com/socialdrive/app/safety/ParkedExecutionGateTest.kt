package com.socialdrive.app.safety

import com.socialdrive.app.domain.MotionState
import com.socialdrive.app.data.DraftSnapshot
import com.socialdrive.app.data.remote.AssetUploadCommand
import com.socialdrive.app.data.remote.RemoteDriveApi
import com.socialdrive.app.data.remote.assetUploads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParkedExecutionGateTest {
    @Test
    fun `stopped dispatcher transition after registration cancels before remote block starts`() =
        runTest(StandardTestDispatcher()) {
            val registered = CompletableDeferred<Unit>()
            val releaseStart = CompletableDeferred<Unit>()
            val gate = ParkedExecutionGate(MotionState.Parked) {
                registered.complete(Unit)
                releaseStart.await()
            }
            var remoteCalls = 0
            var pauses = 0
            var queueState = "RUNNING"
            val result = async {
                gate.execute(onPaused = { pauses += 1; queueState = "PAUSED_MOTION" }) {
                    remoteCalls += 1
                }
            }

            registered.await()
            val transition = async(start = CoroutineStart.UNDISPATCHED) {
                gate.transitionTo(MotionState.Driving)
            }
            releaseStart.complete(Unit)
            transition.await()

            assertIs<ParkedExecutionResult.Paused>(result.await())
            assertEquals(0, remoteCalls)
            assertEquals(1, pauses)
            assertEquals("PAUSED_MOTION", queueState)

            gate.transitionTo(MotionState.Parked)
            val replay = gate.execute(onPaused = {}) { remoteCalls += 1; "resumed" }
            assertEquals("resumed", assertIs<ParkedExecutionResult.Completed<String>>(replay).value)
            assertEquals(1, remoteCalls)
        }

    @Test
    fun `create upload and publish are cooperatively cancelled and never advance`() = runTest {
        val draft = DraftSnapshot("draft", "recording", "request", "title", "description", "preset", "local-recording://recording", "QUEUED")
        for (stage in listOf("create", "upload", "publish")) {
            val gate = ParkedExecutionGate(MotionState.Parked)
            val remote = LatchingRemoteDriveApi()
            val result = async {
                gate.execute(onPaused = {}) {
                    remote.call(stage, draft)
                }
            }

            remote.entered.await()
            gate.transitionTo(MotionState.Unknown)
            assertIs<ParkedExecutionResult.Paused>(result.await(), stage)

            val blocked = gate.execute(onPaused = {}) { remote.call("next", draft) }
            assertIs<ParkedExecutionResult.Paused>(blocked, stage)
            assertEquals(listOf(stage), remote.calls, stage)
        }
    }

    @Test
    fun `parked transition permits an explicit replay after a safe pause`() = runTest {
        val gate = ParkedExecutionGate(MotionState.Driving)
        var calls = 0
        assertIs<ParkedExecutionResult.Paused>(gate.execute(onPaused = {}) { calls += 1 })

        gate.transitionTo(MotionState.Parked)
        val replay = gate.execute(onPaused = {}) { calls += 1; "done" }

        assertEquals("done", assertIs<ParkedExecutionResult.Completed<String>>(replay).value)
        assertEquals(1, calls)
    }
}

private class LatchingRemoteDriveApi : RemoteDriveApi {
    val entered = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()

    suspend fun call(stage: String, draft: DraftSnapshot): Any = when (stage) {
        "create" -> createDrive(draft.clientRequestId, draft)
        "upload" -> uploadAsset("remote", draft.assetUploads().single())
        "publish" -> publishDrive("remote", draft.clientRequestId)
        else -> block(stage)
    }

    override suspend fun createDrive(clientRequestId: String, draft: DraftSnapshot): String = block("create")
    override suspend fun uploadAsset(remoteDriveId: String, command: AssetUploadCommand) = block("upload")
    override suspend fun publishDrive(remoteDriveId: String, clientRequestId: String) = block("publish")

    private suspend fun block(stage: String): Nothing {
        calls += stage
        entered.complete(Unit)
        awaitCancellation()
    }
}
