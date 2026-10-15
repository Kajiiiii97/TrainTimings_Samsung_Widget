package com.traintimings.vvs

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        // First failures with nothing cached yet stay on "Loading…" while we retry.
        val failed = Refresher.refreshAll(applicationContext, keepLoadingOnFailure = runAttemptCount < MAX_RETRIES)
        return if (failed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
