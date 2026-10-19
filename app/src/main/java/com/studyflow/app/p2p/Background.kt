package com.studyflow.app.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studyflow.app.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Android only lets an app run in the background in short, scheduled windows. So the design is:
 *  - while the app is open: live connections (real-time),
 *  - while a timer runs and "stay live" is on: a visible foreground service keeps the connection,
 *  - otherwise: a WorkManager job wakes up roughly every 15 minutes, syncs, publishes, and sleeps.
 * Because every phone stores what it receives, nobody needs to be online at the same moment.
 */
object Background {
    private const val WORK = "studyflow-sync"

    fun ensure(ctx: Context, needed: Boolean) {
        try {
            val wm = WorkManager.getInstance(ctx)
            if (!needed) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) {
            // WorkManager unavailable: foreground sync still works
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Social.get(applicationContext).syncOnce(20_000)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Keeps relay connections alive while a study timer is running, so groups see you "studying now". */
class LiveService : Service() {
    companion object {
        private const val CH = "live"
        private const val ID = 77

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, LiveService::class.java))
            } catch (e: Exception) {
                // not allowed to start right now (e.g. background restrictions): the app still syncs in the foreground
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, LiveService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Live status", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("StudyFlow is live")
            .setContentText("Your groups can see that you are studying")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Social.get(applicationContext).acquire("svc")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Social.get(applicationContext).release("svc")
        super.onDestroy()
    }
}
