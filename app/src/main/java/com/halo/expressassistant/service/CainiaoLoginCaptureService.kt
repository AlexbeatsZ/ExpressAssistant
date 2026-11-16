package com.halo.expressassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.halo.expressassistant.R
import com.halo.expressassistant.api.CainiaoLoginLog
import com.halo.expressassistant.api.CainiaoNativeRuntime
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.api.CainiaoServiceException
import com.halo.expressassistant.data.CainiaoSessionStore
import com.halo.expressassistant.ui.SettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** User-started capture remains active while the user signs into the official app. */
class CainiaoLoginCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val candidates = MutableStateFlow<CainiaoLoginLog.Candidate?>(null)
    private var started = false
    @Volatile private var reader: Process? = null
    @Volatile private var readerPid: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("已停止获取")
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "菜鸟登录获取", NotificationManager.IMPORTANCE_LOW))
        mutableState.value = State(true, "正在请求 Root 授权")
        startForeground(NOTIFICATION, notification(mutableState.value.message))
        scope.launch {
            candidates.collectLatest { candidate ->
                if (candidate != null) {
                    while (isActive) {
                        update("已收到登录信息，正在验证")
                        try {
                            CainiaoRenewal.acceptCapturedLogin(this@CainiaoLoginCaptureService, candidate)
                            finish("菜鸟登录获取成功")
                            return@collectLatest
                        } catch (e: CancellationException) { throw e }
                        catch (e: CainiaoServiceException) {
                            update("${e.kind.message}；仍在获取，可再次点击停止")
                        } catch (_: Exception) {
                            update("登录信息尚未通过验证，继续等待菜鸟登录")
                        }
                        delay(15_000)
                    }
                }
            }
        }
        scope.launch {
            try {
                val info = CainiaoNativeRuntime.verifiedPackage(this@CainiaoLoginCaptureService)
                val uid = checkNotNull(info.applicationInfo).uid
                while (isActive) {
                    readLogs(uid)
                    if (!isActive) break
                    if (readerPid == null) {
                        update("未取得 Root 授权，正在等待重试")
                        delay(15_000)
                    } else {
                        update("正在重新连接菜鸟登录记录")
                        delay(2_000)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) { finish(e.kind.message) }
            catch (_: Exception) { finish("无法启动登录获取，请检查 Root 授权后重试") }
        }
        return START_NOT_STICKY
    }

    private fun readLogs(uid: Int) {
        val parser = CainiaoLoginLog()
        readerPid = null
        val process = ProcessBuilder("su", "-c",
            "echo EA_CAPTURE_PID=\$\$; exec /system/bin/logcat -b main --uid=$uid -T 1 -v raw '*:V'")
            .redirectErrorStream(true).start()
        reader = process
        if (!scope.isActive) {
            process.destroy()
            return
        }
        try {
            process.inputStream.bufferedReader().use { input ->
                while (scope.isActive) {
                    val line = input.readLine() ?: break
                    if (line.startsWith("EA_CAPTURE_PID=")) {
                        readerPid = line.substringAfter('=').toIntOrNull()?.takeIf { it > 1 }
                        update("正在获取，请打开菜鸟登录并查看身份码")
                        continue
                    }
                    if (readerPid == null) continue
                    val existing = if ("cnMobileSid=" in line || "SessionInfo(" in line)
                        runCatching { CainiaoSessionStore.read(this) }.getOrNull() else null
                    val candidate = parser.accept(line, existing) ?: continue
                    if (candidates.value?.sameCredentials(candidate) != true) candidates.value = candidate
                }
            }
        } finally {
            process.destroy()
            reader = null
        }
    }

    private fun update(message: String) {
        if (!scope.isActive || !mutableState.value.running) return
        mutableState.value = State(true, message)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }

    private fun finish(message: String) {
        mutableState.value = State(false, message)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        val process = reader
        val pid = readerPid
        reader = null
        readerPid = null
        process?.destroy()
        // su may retain a root-owned logcat child after the app-side process is destroyed.
        if (pid != null) Thread {
            runCatching {
                val stop = ProcessBuilder("su", "-c", "kill -TERM $pid").redirectErrorStream(true).start()
                if (!stop.waitFor(5, TimeUnit.SECONDS)) stop.destroy()
            }
        }.start()
        candidates.value = null
        if (mutableState.value.running) mutableState.value = State(false, "获取已中断，请重新开始")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(message: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_CAINIAO_LOGIN, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync).setContentTitle("正在获取菜鸟登录").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "停止获取", stop).build()
    }

    data class State(val running: Boolean = false, val message: String = "")

    companion object {
        private const val CHANNEL = "cainiao_login_capture"
        private const val NOTIFICATION = 18770
        private const val ACTION_STOP = "com.halo.expressassistant.STOP_CAINIAO_LOGIN_CAPTURE"
        private val mutableState = MutableStateFlow(State())
        val state = mutableState.asStateFlow()

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CainiaoLoginCaptureService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CainiaoLoginCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
