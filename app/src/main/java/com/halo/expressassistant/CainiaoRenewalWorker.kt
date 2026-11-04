package com.halo.expressassistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.halo.expressassistant.api.CainiaoRenewal
import kotlinx.coroutines.CancellationException

class CainiaoRenewalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        CainiaoRenewal.refresh(applicationContext)
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }
}
