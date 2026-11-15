package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.DumpRepository
import com.example.data.PreferencesRepository
import com.example.service.ExtractionManager
import com.example.service.ExtractionService

class NxDumpApplication : Application() {
    lateinit var preferencesRepository: PreferencesRepository
        private set
    lateinit var dumpRepository: DumpRepository
        private set
    lateinit var extractionManager: ExtractionManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = PreferencesRepository(this)
        dumpRepository = DumpRepository(this, preferencesRepository)
        extractionManager = ExtractionManager()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ExtractionService.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
