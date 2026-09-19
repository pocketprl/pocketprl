package dev.pocketprl.data.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.pocketprl.MainActivity
import dev.pocketprl.PocketPrlApp
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Price alerts: a JobScheduler job polls the price feed and raises one
 * notification when the 24 h change first reaches each multiple of the user's
 * threshold, in either direction. The last band and direction are remembered,
 * so a once-a-minute poll cannot repeat the same alert.
 */
object PriceAlertNotifier {
    const val CHANNEL_ID = "price.v1"
    const val TAG = "PocketPRL/price"
    private const val PREFS = "pocketprl.price_alert"
    private const val KEY_LAST_BAND = "last_band"
    private const val KEY_LAST_SIGN = "last_sign"
    private const val NOTIFICATION_ID = 0x50524C41 // "PRLA"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_price), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notif_channel_price_desc)
                    enableVibration(true)
                },
            )
        }
    }

    /** Forget which band was last announced, so re-enabling the alert starts fresh. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Fires one notification when [change24h] first crosses a multiple of
     * [threshold]. A later, larger move in the same direction fires again at the
     * next multiple; a reversal fires immediately.
     */
    fun maybeNotify(context: Context, change24h: Double?, price: Double, threshold: Double) {
        if (change24h == null || !change24h.isFinite()) return
        if (!price.isFinite() || price <= 0) return
        if (threshold <= 0) return
        val band = (abs(change24h) / threshold).toInt()
        if (band < 1) return
        if (!PaymentNotifier.canPost(context)) {
            Log.w(TAG, "price moved ${"%.2f".format(Locale.US, change24h)}% but POST_NOTIFICATIONS is not granted")
            return
        }
        val sign = if (change24h >= 0) 1 else -1
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_BAND, 0) == band && prefs.getInt(KEY_LAST_SIGN, 0) == sign) return
        prefs.edit().putInt(KEY_LAST_BAND, band).putInt(KEY_LAST_SIGN, sign).apply()

        ensureChannel(context)
        val direction = context.getString(if (change24h >= 0) R.string.price_dir_up else R.string.price_dir_down)
        val text = context.getString(R.string.price_alert_body, direction, String.format(Locale.US, "%.2f", abs(change24h)), Amount.usdPrice(price))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_price_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        Log.i(TAG, "alerted: $text")
    }
}

class PriceAlertJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as? PocketPrlApp ?: return false
        val c = app.container
        if (!c.settings.priceAlert) {
            Log.i(PriceAlertNotifier.TAG, "check skipped: price alerts off")
            return false
        }
        running = c.scope.launch {
            try {
                val quote = c.priceApi.prlFiat()
                if (quote != null) {
                    PriceAlertNotifier.maybeNotify(applicationContext, quote.change24h, quote.fiat, c.settings.priceAlertPercent)
                } else {
                    Log.i(PriceAlertNotifier.TAG, "no quote available")
                }
            } finally {
                // One-shot job: re-arm for the next run.
                if (c.settings.priceAlert) schedule(applicationContext, replace = true)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true // let the system reschedule this run
    }

    companion object {
        private const val JOB_ID = 0x50524C02

        /**
         * Target gap between checks. JobScheduler refuses periodic jobs under 15
         * minutes, so this is a one-shot job that re-arms itself after each run.
         * Doze still defers work, so a minute is the floor, not a promise.
         */
        const val PERIOD_MS = 60_000L

        /** How the interval reads in the UI. */
        const val PERIOD_LABEL = "about every minute"

        /** Returns true when the next check is on the books. Never throws: a refusal is logged, not fatal. */
        fun schedule(context: Context, replace: Boolean = false): Boolean {
            val js = context.getSystemService(JobScheduler::class.java)
            if (!replace && js.getPendingJob(JOB_ID) != null) return true
            return try {
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, PriceAlertJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(PERIOD_MS)
                    // Without a deadline the scheduler may hold the job back indefinitely to batch it.
                    .setOverrideDeadline(PERIOD_MS * 3)
                    .setPersisted(true)
                    .build()
                val result = js.schedule(info)
                if (result == JobScheduler.RESULT_SUCCESS) Log.i(PriceAlertNotifier.TAG, "next check scheduled")
                else Log.w(PriceAlertNotifier.TAG, "JobScheduler refused the price check: result $result")
                result == JobScheduler.RESULT_SUCCESS
            } catch (e: Exception) {
                Log.w(PriceAlertNotifier.TAG, "could not schedule the price check", e)
                false
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
