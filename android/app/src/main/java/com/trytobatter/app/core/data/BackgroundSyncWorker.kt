package com.trytobatter.app.core.data

import android.content.Context
import androidx.work.*
import com.trytobatter.app.BatterApplication
import java.util.concurrent.TimeUnit

class BackgroundSyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as BatterApplication).repository
        return try { repository.load(); if(repository.state.value.connection.paired) repository.syncNow(); Result.success() } catch(e: Exception) { if(runAttemptCount < 3) Result.retry() else Result.failure() }
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("batter-lan-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun once(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("batter-lan-flush", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackgroundSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("batter-lan-periodic"); WorkManager.getInstance(context).cancelUniqueWork("batter-lan-flush") }
    }
}
