package com.wadifainfo.concours.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wadifainfo.concours.ConcoursApp
import com.wadifainfo.concours.MainActivity
import com.wadifainfo.concours.R
import com.wadifainfo.concours.data.*
import com.wadifainfo.concours.ui.latinDigits

/**
 * Periodic on-device check (12 h or 24 h, WorkManager — timing is best effort, never real-time):
 *  1. saved searches with alerts on → newly published, still-open offers not notified before;
 *  2. bookmarked offers → deadline reminders 7, 3, 1 days before and on the last day.
 * Every notification is recorded in the `notified` table first, so nothing is ever sent twice. Only the search
 * criteria go to the server (the same public API the app uses); no identifier, no account.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val NAME = "concours-alerts"; const val CHANNEL = "alerts"; const val CHANNEL_DUE = "deadlines" }

    private lateinit var res: android.content.res.Resources

    override suspend fun doWork(): Result {
        val repo = ConcoursApp.repo(applicationContext)
        val p = repo.prefs
        if (!p.alerts && !p.reminders) return Result.success()
        @Suppress("DEPRECATION")
        val lang = p.uiLang ?: if (applicationContext.resources.configuration.locale.language == "fr") "fr" else "ar"
        // Per-app language is applied to activities only: build resources in that language for the notification texts.
        res = applicationContext.createConfigurationContext(android.content.res.Configuration(applicationContext.resources.configuration)
            .apply { setLocale(java.util.Locale.forLanguageTag(lang)) }).resources
        val canNotify = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        var failed = false

        if (p.alerts) for (s in repo.saved.notifying()) {
            val page = runCatching { repo.api.searchOffers(lang, s.query().copy(sort = 4), 1, 30) }.getOrNull()
            if (page == null) { failed = true; continue }
            val keys = page.items.map { "new:${it.id}" }
            val seen = repo.notified.existing(keys).toSet()
            val fresh = Logic.newForAlert(page.items, seen, baseline = !p.baselined(s.id))
            // Record everything currently listed (baseline or not) before notifying: no repeats, even if posting fails.
            repo.notified.add(keys.map { Notified(it, System.currentTimeMillis()) })
            p.setBaselined(s.id)
            if (fresh.isNotEmpty()) notify(("s" + s.id).hashCode(), CHANNEL,
                latinDigits(res.getQuantityString(R.plurals.alert_new, fresh.size, fresh.size, s.label)),
                fresh.take(3).joinToString(" · ") { it.grade }, if (fresh.size == 1) fresh[0].id else null)
        }

        // Without the permission nothing is recorded: the reminder is still due once notifications are allowed again.
        if (p.reminders && canNotify) for (f in repo.favorites.offersWithDeadline()) {
            val id = f.refId.toLongOrNull() ?: continue
            val key = Logic.reminderKey(id, f.deadline) ?: continue
            if (repo.notified.existing(listOf(key)).isNotEmpty()) continue
            repo.notified.add(listOf(Notified(key, System.currentTimeMillis())))
            val days = Logic.daysLeft(f.deadline)!!.toInt()
            notify(key.hashCode(), CHANNEL_DUE,
                if (days == 0) res.getString(R.string.due_today) else latinDigits(res.getQuantityString(R.plurals.due_in, days, days)),
                f.title, id)
        }
        repo.notified.prune(System.currentTimeMillis() - 120L * 24 * 3600 * 1000)
        return if (failed) Result.retry() else Result.success()
    }

    private fun notify(id: Int, channel: String, title: String, text: String, offerId: Long?) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            if (offerId != null) data = Uri.parse("https://www.wadifa-info.com/$offerId/")
        }
        val pi = PendingIntent.getActivity(applicationContext, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationManagerCompat.from(applicationContext).notify(id, NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build())
    }
}
