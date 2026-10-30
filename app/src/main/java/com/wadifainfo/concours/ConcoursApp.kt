package com.wadifainfo.concours

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.launch
import com.wadifainfo.concours.data.Repo
import com.wadifainfo.concours.work.AlertWorker
import java.util.concurrent.TimeUnit

class ConcoursApp : Application() {
    val repo by lazy { Repo(this) }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(AlertWorker.CHANNEL, getString(R.string.channel_new), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(AlertWorker.CHANNEL_DUE, getString(R.string.channel_due), NotificationManager.IMPORTANCE_HIGH)))
        // Users updating from 2.0/2.1 already follow offers: make sure their reminder check exists.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { repo.prefs.hasFollowed = repo.favorites.offerCount() > 0; ensureWork(this@ConcoursApp) }
        }
    }

    companion object {
        fun repo(ctx: Context) = (ctx.applicationContext as ConcoursApp).repo

        /**
         * One periodic check serves both features: saved-search alerts (opt-in) and deadline reminders for followed offers
         * (on by default once something is followed). Scheduled while either is needed, cancelled otherwise. WorkManager
         * persists it across reboots; timing is best effort (Doze), which reminder catch-up in Logic.reminderKey absorbs.
         */
        fun ensureWork(ctx: Context) {
            val p = repo(ctx).prefs
            val wm = WorkManager.getInstance(ctx)
            if (!(p.alerts || (p.reminders && p.hasFollowed))) { wm.cancelUniqueWork(AlertWorker.NAME); return }
            // Reminders alone run every 12 h so a calendar day is rarely skipped; alerts use the chosen frequency.
            val hours = if (p.alerts) p.alertHours else 12
            wm.enqueueUniquePeriodicWork(AlertWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE,
                // No network constraint: reminders work offline; the saved-search part retries itself when offline.
                PeriodicWorkRequestBuilder<AlertWorker>(hours.toLong(), TimeUnit.HOURS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        }
    }
}
