package com.halo.expressassistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (com.halo.expressassistant.data.Store.clearLegacyThemePreferences(this)) {
            com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(this)
        }
        PhoneControl.start(this)
        // 15 分钟为 WorkManager 周期下限；Worker 内部按设置（默认关闭 / 用户自定分钟）决定是否执行
        val request = PeriodicWorkRequestBuilder<TrackingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("express_tracking", ExistingPeriodicWorkPolicy.UPDATE, request)
        val renewal = PeriodicWorkRequestBuilder<CainiaoRenewalWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cainiao_material_renewal", ExistingPeriodicWorkPolicy.KEEP, renewal
        )
        // 记录当前前台 Activity：小组件刷新等后台入口需要 Activity 上下文跑四源同步
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            private var readClipboardOnFocus = false
            private val focusListeners = mutableMapOf<Activity, android.view.ViewTreeObserver.OnWindowFocusChangeListener>()

            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
                activity.window.decorView.post { checkClipboard(activity) }
            }

            override fun onActivityPaused(activity: Activity) {
                if (topActivity === activity) topActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                    if (focused) checkClipboard(activity)
                }
                focusListeners[activity] = listener
                activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            }

            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    readClipboardOnFocus = true
                    PackageAutoImport.scanSms(activity)
                    PackageAutoImport.resumePendingLookups(activity)
                }
            }

            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                focusListeners.remove(activity)?.let {
                    activity.window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it)
                }
            }

            private fun checkClipboard(activity: Activity) {
                if (readClipboardOnFocus && topActivity === activity && activity.hasWindowFocus()) {
                    readClipboardOnFocus = false
                    PackageAutoImport.readClipboard(activity)
                }
            }
        })
    }

    companion object {
        lateinit var instance: App
            private set

        /** 当前在前台的 Activity；无前台 Activity 时为 null */
        @Volatile
        var topActivity: Activity? = null
            private set
    }
}
