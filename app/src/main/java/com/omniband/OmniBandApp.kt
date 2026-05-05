package com.omniband

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.omniband.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class OmniBandApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging()
        createNotificationChannels()
    }

    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Connection service channel
        NotificationChannel(
            CHANNEL_DEVICE_SERVICE,
            "Device Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps your gadget connected in the background"
            setShowBadge(false)
            notificationManager.createNotificationChannel(this)
        }

        // Alerts channel (incoming calls, notifications from band)
        NotificationChannel(
            CHANNEL_ALERTS,
            "Device Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications from your connected devices"
            notificationManager.createNotificationChannel(this)
        }

        // Sleep tracking channel
        NotificationChannel(
            CHANNEL_SLEEP,
            "Sleep Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sleep as Android integration status"
            setShowBadge(false)
            notificationManager.createNotificationChannel(this)
        }
    }

    companion object {
        const val CHANNEL_DEVICE_SERVICE = "omniband_device_service"
        const val CHANNEL_ALERTS = "omniband_alerts"
        const val CHANNEL_SLEEP = "omniband_sleep"
    }
}
