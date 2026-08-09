package com.arflix.tv.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arflix.tv.data.repository.CloudSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val cloudSyncRepository: CloudSyncRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Executing background cloud sync recovery")

        // If not dirty, nothing to do. Check persisted state too because the
        // process may have restarted after the local change was queued.
        if (!cloudSyncRepository.hasPendingLocalChanges()) {
            Log.i(TAG, "Cloud state is not dirty. Skipping sync.")
            return Result.success()
        }

        return try {
            val result = cloudSyncRepository.pushToCloud()
            if (result.isSuccess) {
                Log.i(TAG, "Background cloud sync recovery succeeded")
                Result.success()
            } else {
                Log.w(TAG, "Background cloud sync recovery failed, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in background cloud sync: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CloudSyncWorker"
        private const val WORK_NAME = "CloudSyncRecoveryWork"

        fun enqueueRecovery(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE, // Restart backoff if a new invalidation occurs
                request
            )
        }
    }
}
