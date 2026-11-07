package com.halo.expressassistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.api.CainiaoServiceException
import kotlinx.coroutines.CancellationException

class CainiaoRenewalWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        CainiaoRenewal.refresh(applicationContext)
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (e: CainiaoServiceException) {
        if (e.kind in setOf(CainiaoServiceException.Kind.LOGIN_EXPIRED, CainiaoServiceException.Kind.NO_SESSION,
                CainiaoServiceException.Kind.AUTH_RUNTIME_UNAVAILABLE, CainiaoServiceException.Kind.AUTO_RENEW_NOT_CONFIGURED))
            Result.success() else Result.retry()
    }
    catch (_: Exception) { Result.retry() }
}
