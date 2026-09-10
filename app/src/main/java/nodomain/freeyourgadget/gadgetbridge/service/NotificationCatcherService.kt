package nodomain.freeyourgadget.gadgetbridge.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.ble.BleManager
import nodomain.freeyourgadget.gadgetbridge.data.repository.UserPreferencesRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Forwards phone notifications to the band (notification mirroring).
 *
 * Requires the user to grant *Notification access* in system settings; Android
 * delivers posted/removed notifications here. Only packages enabled in
 * [UserPreferencesRepository.enabledNotifApps] are mirrored, and only when the
 * master toggle ([UserPreferencesRepository.notifMirrorEnabled]) is on.
 *
 * Ongoing notifications, group summaries and our own app's notifications are
 * never mirrored.
 */
@AndroidEntryPoint
class NotificationCatcherService : NotificationListenerService() {

    @Inject
    lateinit var bleManager: BleManager
    @Inject
    lateinit var prefs: UserPreferencesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Recently forwarded content per notification key (`package|id`), to suppress
     * re-posts of identical text (apps often re-post/update the same notification,
     * which would otherwise buzz the band again for an old message).
     */
    private data class Forwarded(val title: String, val body: String, val atMs: Long)

    private val recentForwards = LinkedHashMap<String, Forwarded>()

    /** Identical content forwarded within this window is treated as a re-post. */
    private companion object {
        const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        const val MAX_TRACKED_KEYS = 100
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        val notification = sbn.notification
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        scope.launch {
            if (!prefs.notifMirrorEnabled.first()) return@launch
            if (isQuietNow()) {
                Timber.v("NotificationCatcher: quiet hours — skipping #%d", sbn.id)
                return@launch
            }
            if (sbn.packageName !in prefs.enabledNotifApps.first()) {
                Timber.v("NotificationCatcher: %s not enabled — skipping", sbn.packageName)
                return@launch
            }
            val extras: Bundle = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) {
                Timber.v(
                    "NotificationCatcher: empty notification from %s — skipping",
                    sbn.packageName
                )
                return@launch
            }
            val dedupKey = "${sbn.packageName}|${sbn.id}"
            val isDupe = synchronized(recentForwards) {
                recentForwards[dedupKey]?.let { prev ->
                    prev.title == title && prev.body == text &&
                            System.currentTimeMillis() - prev.atMs < DEDUP_WINDOW_MS
                } ?: false
            }
            if (isDupe) {
                Timber.i(
                    "NotificationCatcher: re-post of recent #%d from %s — skipping",
                    sbn.id, sbn.packageName
                )
                return@launch
            }
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (e: Exception) {
                sbn.packageName
            }
            Timber.i(
                "NotificationCatcher: mirroring #%d from %s: %s",
                sbn.id, sbn.packageName, title.take(60)
            )
            synchronized(recentForwards) {
                recentForwards["${sbn.packageName}|${sbn.id}"] =
                    Forwarded(title, text, System.currentTimeMillis())
                while (recentForwards.size > MAX_TRACKED_KEYS) {
                    recentForwards.remove(recentForwards.keys.first())
                }
            }
            bleManager.sendNotification(sbn.id, sbn.packageName, title, text, appName)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch {
            if (!prefs.notifMirrorEnabled.first()) return@launch
            bleManager.dismissNotification(sbn.id)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun isQuietNow(): Boolean {
        if (!prefs.quietHoursEnabled.first()) return false
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                cal.get(java.util.Calendar.MINUTE)
        return prefs.isQuietNow(
            nowMin,
            prefs.quietHoursStartMin.first(),
            prefs.quietHoursEndMin.first()
        )
    }
}
