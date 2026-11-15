package com.example.service

import com.example.data.ExtractionProgress
import com.example.data.ExtractionRequest
import com.example.data.ExtractionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ExtractionQueueState(
    val activeJob: ExtractionProgress? = null,
    val pendingCount: Int = 0,
    val history: List<ExtractionProgress> = emptyList()
)

class ExtractionManager {
    private val _state = MutableStateFlow(ExtractionQueueState())
    val state: StateFlow<ExtractionQueueState> = _state.asStateFlow()

    private val pendingQueue = ArrayDeque<Pair<String, ExtractionRequest>>()
    private val cancellationFlags = ConcurrentHashMap<String, AtomicBoolean>()

    @Synchronized
    fun enqueue(request: ExtractionRequest): String {
        val jobId = UUID.randomUUID().toString().take(8)
        cancellationFlags[jobId] = AtomicBoolean(false)
        pendingQueue.addLast(jobId to request)
        val initial = ExtractionProgress(
            jobId = jobId,
            gameTitle = request.game.titleName,
            titleIdHex = request.game.titleIdHex,
            target = request.target,
            mergedUpdateVersion = if (request.mergeUpdate) request.selectedUpdate?.displayVersion else null,
            status = ExtractionStatus.QUEUED,
            phaseLabel = "Queued...",
            currentFile = "",
            filesDone = 0,
            totalFiles = 0,
            bytesDone = 0L,
            totalBytes = 0L,
            outputFolderLabel = ""
        )
        _state.update { curr ->
            if (curr.activeJob == null) {
                curr.copy(activeJob = initial, pendingCount = pendingQueue.size)
            } else {
                curr.copy(pendingCount = pendingQueue.size)
            }
        }
        return jobId
    }

    @Synchronized
    fun pollNext(): Pair<String, ExtractionRequest>? {
        val next = pendingQueue.removeFirstOrNull()
        _state.update { it.copy(pendingCount = pendingQueue.size) }
        return next
    }

    fun isCancelled(jobId: String): Boolean = cancellationFlags[jobId]?.get() == true

    fun cancelActive() {
        val active = _state.value.activeJob ?: return
        cancellationFlags[active.jobId]?.set(true)
    }

    fun updateActive(progress: ExtractionProgress) {
        _state.update { it.copy(activeJob = progress) }
    }

    fun completeActive(finalProgress: ExtractionProgress) {
        cancellationFlags.remove(finalProgress.jobId)
        _state.update { curr ->
            curr.copy(
                activeJob = null,
                pendingCount = pendingQueue.size,
                history = listOf(finalProgress) + curr.history.take(29)
            )
        }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
    }
}
