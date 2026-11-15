package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.NxDumpApplication
import com.example.R
import com.example.core.ExtractionProgressCallback
import com.example.core.io.BinaryUtils
import com.example.data.ExtractionProgress
import com.example.data.ExtractionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ExtractionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isProcessing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as NxDumpApplication
        if (intent?.action == ACTION_CANCEL) {
            app.extractionManager.cancelActive()
            return START_NOT_STICKY
        }

        startAsForeground(
            title = "Preparing extraction...",
            text = "Initializing cryptographic engine",
            progressPct = 0,
            indeterminate = true
        )

        if (isProcessing.compareAndSet(false, true)) {
            serviceScope.launch {
                val pm = getSystemService(PowerManager::class.java)
                val wakeLock = runCatching {
                    pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${BuildConfig.APPLICATION_ID}:extraction")
                        ?.apply { acquire(45 * 60 * 1000L) }
                }.getOrNull()
                try {
                    processQueue(app)
                } finally {
                    runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                    isProcessing.set(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processQueue(app: NxDumpApplication) {
        val manager = app.extractionManager
        val nm = getSystemService(NotificationManager::class.java)
        var lastNotifMs = 0L
        var lastUiUpdateMs = 0L

        val progressLock = Any()

        while (true) {
            val item = manager.pollNext() ?: break
            val (jobId, request) = item
            val config = app.preferencesRepository.setupConfigFlow.first()
            val mergedVer = if (request.mergeUpdate) request.selectedUpdate?.displayVersion else null
            val jobStartMs = System.currentTimeMillis()
            var maxFilesDone = 0
            var maxBytesDone = 0L

            var current = ExtractionProgress(
                jobId = jobId,
                gameTitle = request.game.titleName,
                titleIdHex = request.game.titleIdHex,
                target = request.target,
                mergedUpdateVersion = mergedVer,
                status = ExtractionStatus.RUNNING,
                phaseLabel = "Starting...",
                currentFile = "",
                filesDone = 0,
                totalFiles = 0,
                bytesDone = 0L,
                totalBytes = 0L,
                outputFolderLabel = config.outputDirName ?: "Output",
                startedAtMs = jobStartMs
            )
            manager.updateActive(current)

            try {
                val outLabel = app.dumpRepository.runExtraction(
                    config = config,
                    request = request,
                    callbacks = ExtractionProgressCallback(
                        onPhase = { phase ->
                            synchronized(progressLock) {
                                current = current.copy(phaseLabel = phase)
                                manager.updateActive(current)
                            }
                            nm?.notify(NOTIFICATION_ID, buildNotification(current.gameTitle, phase, 0, true))
                        },
                        onFileProgress = { file, filesDone, totalFiles, bytesDone, totalBytes ->
                            var notifDetail: String? = null
                            var notifPct = 0
                            synchronized(progressLock) {
                                if (filesDone > maxFilesDone) maxFilesDone = filesDone
                                if (bytesDone > maxBytesDone) maxBytesDone = bytesDone
                                val now = System.currentTimeMillis()
                                val elapsedMs = (now - jobStartMs).coerceAtLeast(1L)
                                val speedBps = (maxBytesDone * 1000L) / elapsedMs
                                val isDone = maxFilesDone >= totalFiles && totalFiles > 0
                                if (isDone || now - lastUiUpdateMs >= 100L) {
                                    lastUiUpdateMs = now
                                    current = current.copy(
                                        currentFile = file,
                                        filesDone = maxFilesDone,
                                        totalFiles = totalFiles,
                                        bytesDone = maxBytesDone,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speedBps
                                    )
                                    manager.updateActive(current)
                                }
                                if (isDone || now - lastNotifMs > 400L) {
                                    lastNotifMs = now
                                    notifPct = if (totalBytes > 0L) ((maxBytesDone.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                                    val speedStr = if (speedBps > 0L) " • ${BinaryUtils.formatBytes(speedBps)}/s" else ""
                                    notifDetail = "$maxFilesDone/$totalFiles • ${BinaryUtils.formatBytes(maxBytesDone)}$speedStr"
                                }
                            }
                            notifDetail?.let { detail ->
                                nm?.notify(NOTIFICATION_ID, buildNotification(current.gameTitle, detail, notifPct, false))
                            }
                        },
                        isCancelled = { manager.isCancelled(jobId) }
                    )
                )

                val done = synchronized(progressLock) {
                    current.copy(
                        status = ExtractionStatus.COMPLETED,
                        phaseLabel = "Extraction complete",
                        filesDone = maxFilesDone.coerceAtLeast(current.totalFiles),
                        bytesDone = maxBytesDone.coerceAtLeast(current.totalBytes),
                        outputFolderLabel = outLabel,
                        finishedAtMs = System.currentTimeMillis()
                    )
                }
                manager.completeActive(done)
                showResultNotification(nm, "${request.game.titleName} extracted", "Saved to $outLabel")
            } catch (e: InterruptedException) {
                val cancelled = current.copy(
                    status = ExtractionStatus.CANCELLED,
                    phaseLabel = "Cancelled by user",
                    finishedAtMs = System.currentTimeMillis()
                )
                manager.completeActive(cancelled)
            } catch (e: Exception) {
                val failed = current.copy(
                    status = ExtractionStatus.FAILED,
                    phaseLabel = "Extraction failed",
                    errorMessage = e.message ?: "Unknown error",
                    finishedAtMs = System.currentTimeMillis()
                )
                manager.completeActive(failed)
                showResultNotification(nm, "${request.game.titleName} failed", e.message ?: "Error")
            }
        }
    }

    private fun startAsForeground(title: String, text: String, progressPct: Int, indeterminate: Boolean) {
        val notification = buildNotification(title, text, progressPct, indeterminate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progressPct: Int,
        indeterminate: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ExtractionService::class.java).apply { action = ACTION_CANCEL }
        val cancelPi = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setProgress(100, progressPct.coerceIn(0, 100), indeterminate)
            .addAction(0, getString(R.string.action_cancel), cancelPi)
            .build()
    }

    private fun showResultNotification(nm: NotificationManager?, title: String, text: String) {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentPi = PendingIntent.getActivity(
            this,
            2,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        nm?.notify(RESULT_NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "dump_extraction_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        const val ACTION_CANCEL = "${BuildConfig.APPLICATION_ID}.action.CANCEL_EXTRACTION"

        fun start(context: Context) {
            val intent = Intent(context, ExtractionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
