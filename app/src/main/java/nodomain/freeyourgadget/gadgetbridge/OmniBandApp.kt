package nodomain.freeyourgadget.gadgetbridge

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import dagger.hilt.android.HiltAndroidApp
import nodomain.freeyourgadget.gadgetbridge.ble.BleManager
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class OmniBandApp : Application() {

    @Inject
    lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        initLogging()
        createNotificationChannels()
        observeAppForeground()
    }

    /**
     * Tracks whether any activity is visible and pushes it to [BleManager], which
     * uses it (with Sleep as Android tracking state) for the adaptive HR CONTINUE
     * rate. No extra dependency — plain ActivityLifecycleCallbacks.
     */
    private fun observeAppForeground() {
        var startedActivities = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (++startedActivities == 1) bleManager.setAppForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) bleManager.setAppForeground(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
