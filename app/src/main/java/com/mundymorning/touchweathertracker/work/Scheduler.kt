package com.mundymorning.touchweathertracker.work

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Battery-conscious background refresh. Android enforces a 15-minute minimum for periodic work and may delay it in Doze / App Standby,
 * so the app never claims instant delivery. Normal: every 30 min. Event mode: every 15 min.
 */
object Scheduler {
    private const val NAME = "twt_poll"

    fun schedule(ctx: Context, eventMode: Boolean) {
        val minutes = if (eventMode) 15L else 30L
        val req = PeriodicWorkRequestBuilder<PollWorker>(minutes, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun refreshNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<PollWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
