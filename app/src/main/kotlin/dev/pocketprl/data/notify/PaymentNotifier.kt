package dev.pocketprl.data.notify

import android.Manifest
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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.pocketprl.MainActivity
import dev.pocketprl.PocketPrlApp
import dev.pocketprl.R
import dev.pocketprl.core.chain.Amount
import dev.pocketprl.core.chain.Network
import dev.pocketprl.data.AppIcon
import dev.pocketprl.data.LauncherIconManager
import dev.pocketprl.data.db.TxKind
import dev.pocketprl.data.db.TxRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Incoming-payment notifications. A JobScheduler job runs the normal sync
 * against each wallet's public address set, which needs no unlock. Whichever
 * sync discovers an incoming transaction, this job's or the dashboard's, the
 * repository reports it and [AppContainer] raises one notification per transaction.
 */
object PaymentNotifier {
    /** Importance cannot be raised on an existing channel, hence a versioned id. */
    const val CHANNEL_ID = "payments.v2"
    private const val LEGACY_CHANNEL_ID = "payments"
    const val TAG = "PocketPRL/notify"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(LEGACY_CHANNEL_ID) != null) nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_payments), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notif_channel_payments_desc)
                    enableVibration(true)
                },
            )
        }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** One notification per transaction, keyed by txid, so a [confirmed] one replaces the pending one. */
    fun notify(context: Context, txs: List<TxRow>, network: Network, icon: AppIcon, hideAmounts: Boolean, walletName: String? = null, confirmed: Boolean = false) {
        if (txs.isEmpty()) return
        if (!canPost(context)) { Log.w(TAG, "${txs.size} new incoming, but POST_NOTIFICATIONS is not granted"); return }
        Log.i(TAG, "notifying ${txs.size} ${if (confirmed) "confirmed" else "new"} incoming for ${walletName ?: "the wallet"}")
        ensureChannel(context)
        // The launcher alias controls the home-screen icon; the large icon is how the
        // chosen icon reaches the notification, which the alias cannot touch.
        val large = LauncherIconManager.bitmap(context, icon, context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width))
        val nm = context.getSystemService(NotificationManager::class.java)
        for (tx in txs) {
            val base = when {
                tx.kind == TxKind.MINED -> context.getString(if (confirmed) R.string.notif_block_confirmed else R.string.notif_block)
                confirmed -> context.getString(R.string.notif_payment_confirmed)
                else -> context.getString(R.string.notif_payment_received)
            }
            val title = if (walletName != null) context.getString(R.string.notif_title_wallet, base, walletName) else base
            val text = when {
                hideAmounts -> context.getString(R.string.notif_hidden)
                tx.kind == TxKind.MINED -> context.getString(R.string.notif_mined, Amount.pretty(tx.amount), network.ticker, Network.COINBASE_MATURITY)
                tx.height <= 0 -> context.getString(R.string.notif_waiting, Amount.pretty(tx.amount), network.ticker)
                confirmed -> context.getString(R.string.notif_in_block, Amount.pretty(tx.amount), network.ticker, Amount.group(tx.height))
                else -> context.getString(R.string.notif_amount, Amount.pretty(tx.amount), network.ticker)
            }
            // Distinct data per txid so each notification gets its own PendingIntent that
            // opens straight to that transaction, instead of one shared intent to the root.
            val open = PendingIntent.getActivity(
                context, tx.txid.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("pocketprl://tx/${tx.txid}")
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(large)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(if (hideAmounts) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
                .build()
            nm.notify(tx.txid.hashCode(), n)
        }
    }
}

class PaymentCheckJob : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as? PocketPrlApp ?: return false
        val c = app.container
        val wallets = c.registry.wallets
        if (!c.settings.notifyIncoming || wallets.isEmpty()) {
            Log.i(PaymentNotifier.TAG, "check skipped: notifications off or no wallets")
            return false
        }
        running = c.scope.launch {
            try {
                for (w in wallets) {
                    // The unlocked wallet on screen is already polled by the dashboard through the same path.
                    val ctx = c.context(w.id) ?: continue
                    val onScreen = c.isInForeground && c.registry.activeId == w.id && ctx.session.isUnlocked
                    if (onScreen) continue
                    val ok = runCatching { ctx.repository.sync(receiveOnly = true) }.getOrElse { Log.w(PaymentNotifier.TAG, "check failed for ${w.name}: ${it.message}"); false }
                    Log.i(PaymentNotifier.TAG, "checked ${w.name}: ${if (ok) "ok" else "incomplete"}")
                }
            } finally {
                // One-shot job: re-arm for the next run.
                if (c.settings.notifyIncoming) schedule(applicationContext, replace = true)
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
        private const val JOB_ID = 0x50524C // "PRL"

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
                val info = JobInfo.Builder(JOB_ID, ComponentName(context, PaymentCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(PERIOD_MS)
                    // Without a deadline the scheduler may hold the job back indefinitely to batch it.
                    .setOverrideDeadline(PERIOD_MS * 3)
                    .setPersisted(true)
                    .build()
                val result = js.schedule(info)
                if (result == JobScheduler.RESULT_SUCCESS) Log.i(PaymentNotifier.TAG, "next check scheduled")
                else Log.w(PaymentNotifier.TAG, "JobScheduler refused the payment check: result $result")
                result == JobScheduler.RESULT_SUCCESS
            } catch (e: Exception) {
                Log.w(PaymentNotifier.TAG, "could not schedule the payment check", e)
                false
            }
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }
    }
}
