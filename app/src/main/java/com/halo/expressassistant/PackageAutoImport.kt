package com.halo.expressassistant

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halo.expressassistant.api.ManualPackages
import com.halo.expressassistant.api.WaybillRules
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PackageAutoImport {
    const val PREFERENCES = "express_auto_import"
    val smsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun smsEnabled(context: Context) = preferences(context).getBoolean("sms_enabled", true)
    fun clipboardEnabled(context: Context) = preferences(context).getBoolean("clipboard_enabled", true)
    fun setSmsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("sms_enabled", enabled).apply()
        if (enabled) scanSms(context)
    }
    fun setClipboardEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("clipboard_enabled", enabled).apply()
    }
    fun hasSmsPermission(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED

    fun initialPermissions(context: Context): Array<String> {
        val prefs = preferences(context)
        if (prefs.getBoolean("permission_requested", false)) return emptyArray()
        prefs.edit().putBoolean("permission_requested", true).apply()
        return smsPermissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    fun scanSms(context: Context) {
        if (!smsEnabled(context) || !hasSmsPermission(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sms_package_import", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SmsPackageImportWorker>().build()
        )
    }

    fun resumePendingLookups(context: Context) {
        Store.items(context).filter {
            it.source == "manual" && it.latestTime.isBlank() && it.latestText.isBlank() &&
                !it.isCompleted() && WaybillRules.isValid(it.mailNo)
        }.forEach { enqueueLookup(context, it.mailNo) }
    }

    fun receiveSms(context: Context, content: String, sentAt: Long): Operation? {
        if (!smsEnabled(context)) return null
        val candidates = WaybillRules.smsCandidates(content)
        val (key, values) = if (candidates.waybills.isNotEmpty()) "waybills" to candidates.waybills
            else "pickup_codes" to candidates.pickupCodes
        if (values.isEmpty()) return null
        // WorkManager 的 Data 上限为 10 KiB，多段短信按批持久入队。
        return WorkManager.getInstance(context).enqueue(values.chunked(100).map { batch ->
            OneTimeWorkRequestBuilder<SmsPackageImportWorker>()
                .setInputData(workDataOf(key to batch.toTypedArray(), "sent_at" to sentAt)).build()
        })
    }

    fun readClipboard(activity: Activity) {
        if (!clipboardEnabled(activity) || !activity.hasWindowFocus()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        } catch (_: SecurityException) { return }
        if (text.isBlank()) return
        val candidates = WaybillRules.extract(text)
        if (importWaybills(activity, candidates) > 0) {
            android.widget.Toast.makeText(activity, "已从剪贴板导入快递", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun importWaybills(context: Context, waybills: List<String>): Int {
        var added = 0
        waybills.map(WaybillRules::normalise).distinct().filter(WaybillRules::isValid).forEach { mail ->
            val existing = Store.items(context).any { ManualPackages.matches(it, mail) }
            val hidden = Store.xiaomiHidden(context).any { ManualPackages.matches(it, mail) }
            if (!existing && !hidden) {
                ManualPackages.add(context, mail, null, restoreHidden = false)
                enqueueLookup(context, mail)
                added++
            }
        }
        return added
    }

    fun enqueueLookup(context: Context, mail: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "import_lookup_$mail", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImportedPackageLookupWorker>()
                .setInputData(workDataOf("waybill" to mail))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}

class SmsPackageImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!PackageAutoImport.smsEnabled(ctx)) return@withContext Result.success()
        inputData.getStringArray("waybills")?.let {
            PackageAutoImport.importWaybills(ctx, it.toList())
            return@withContext Result.success()
        }
        inputData.getStringArray("pickup_codes")?.let {
            val saved = Store.importSmsPickupCodes(ctx, it.toList(), inputData.getLong("sent_at", 0L))
            return@withContext if (saved) Result.success() else Result.retry()
        }
        if (!PackageAutoImport.hasSmsPermission(ctx)) return@withContext Result.success()
        val prefs = ctx.getSharedPreferences(PackageAutoImport.PREFERENCES, Context.MODE_PRIVATE)
        val cursorId = prefs.getLong("sms_cursor", 0)
        val backfill = !prefs.getBoolean("sms_pickup_backfilled", false)
        val cutoff = System.currentTimeMillis() - Store.SMS_PICKUP_LOOKBACK
        val idColumn = Telephony.Sms._ID
        val dateColumn = Telephony.Sms.DATE
        val selection = if (backfill) "$idColumn > ? OR $dateColumn >= ?" else "$idColumn > ?"
        val args = if (backfill) arrayOf(cursorId.toString(), cutoff.toString()) else arrayOf(cursorId.toString())
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(idColumn, Telephony.Sms.BODY, dateColumn, Telephony.Sms.DATE_SENT),
                selection, args, "$idColumn ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (isStopped) return@withContext Result.retry()
                    if (!PackageAutoImport.smsEnabled(ctx)) return@withContext Result.success()
                    val id = cursor.getLong(0)
                    val candidates = WaybillRules.smsCandidates(cursor.getString(1).orEmpty())
                    val sentAt = cursor.getLong(3).takeIf { it > 0 } ?: cursor.getLong(2)
                    if (!Store.importSmsPickupCodes(ctx, candidates.pickupCodes, sentAt)) return@withContext Result.retry()
                    if (id > cursorId) {
                        PackageAutoImport.importWaybills(ctx, candidates.waybills)
                        if (!prefs.edit().putLong("sms_cursor", id).commit()) return@withContext Result.retry()
                    }
                }
            } ?: return@withContext Result.retry()
            if (backfill && !prefs.edit().putBoolean("sms_pickup_backfilled", true).commit()) Result.retry() else Result.success()
        } catch (_: SecurityException) { Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class ImportedPackageLookupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mail = inputData.getString("waybill") ?: return Result.failure()
        if (!WaybillRules.isValid(mail)) return Result.failure()
        val item = Store.items(applicationContext).firstOrNull { ManualPackages.matches(it, mail) }
            ?: return Result.success()
        if (item.source != "manual" || item.isCompleted()) return Result.success()
        return try {
            withContext(Dispatchers.IO) { ManualPackages.refresh(applicationContext, item) }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
