package com.halo.expressassistant.ui

import android.app.Activity
import android.util.Log
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 拼多多物流轨迹 · 后台静默补抓
 *
 * 在每次同步完成 / App 回到前台后自动运行（隐藏 WebView，无界面打扰）：
 * - 优先抓「派送中 > 运输中（待收货）」的订单；单轮最多 12 单、每单间隔 3s（防风控/省电）
 * - 只自动补抓活跃单；已完成单在详情页按需加载；轨迹**始终保存**（pdd_traces），点开详情秒开
 */
object PddTraceBackfill {
    private val TAG = "PddTraceBackfill"
    private val running = AtomicBoolean(false)
    private val lastRunAt = AtomicLong(0L)
    private const val MAX_PER_RUN = 8
    private const val GAP_MS = 1500L
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** 静默补抓（force=true 跳过 10 分钟限流：同步完成后调用，立刻回填缓存到卡片并补抓过期单） */
    fun backfill(act: Activity, scope: CoroutineScope, force: Boolean = false) {
        if (SyncEngine.isRunning) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRunAt.get() < MIN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        lastRunAt.set(now)
        scope.launch {
            try {
                val accounts = Store.accounts(act, Store.CH_PDD).filter { it.enabled }
                if (accounts.isEmpty()) return@launch
                val items = Store.items(act).filter { it.source == "pdd" && !it.orderOnly && !it.isCompleted() }
                if (items.isEmpty()) return@launch
                val refreshList = {
                    try {
                        (act as? com.halo.expressassistant.ui.MainActivity)?.let { ma ->
                            ma.runOnUiThread { ma.reload() }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "reload fail: $e")
                    }
                }
                // 先回填历史缓存 → 卡片轨迹外显（列表只存了状态提示，真实轨迹在缓存里）
                items.forEach { PddTraceFetcher.applyCachedTraceToItem(act, it) }
                refreshList()
                // Known waybills can obtain the Cainiao code without reopening PDD's WebView.
                items.filter { it.trackingNumber.isNotBlank() && it.pickupCode.isBlank() && it.isAwaitingPickup() }
                    .take(MAX_PER_RUN).forEach { PddTraceFetcher.refreshCainiaoPickup(act, it.mailNo) }
                refreshList()
                val need = items.filter {
                    Store.pddTraceNeedsRefresh(act, it.mailNo, done = it.state == 3)
                }.sortedWith(
                    compareBy(
                        { if (it.state == 5) 0 else if (it.state <= 1) 1 else 2 },
                        { Store.pddTraceLastFetch(act, it.mailNo) }
                    )
                ).take(MAX_PER_RUN)
                if (need.isEmpty()) {
                    Log.i(TAG, "无待补抓（缓存新鲜）")
                    return@launch
                }
                Log.i(TAG, "后台补抓 ${need.size}/${items.size} 单（先抓未缓存的活跃单）")
                var ok = 0
                var fail = 0
                for (item in need) {
                    if (SyncEngine.isRunning) break
                    val account = Store.accountForItem(act, item)
                    if (account == null) {
                        fail++
                        continue
                    }
                    val cookies = Store.cookieOf(account.payload)
                    try {
                        val points = PddTraceFetcher.fetchWith(act, item, cookies, silent = true)
                        if (points != null && points.isNotEmpty()) {
                            ok++
                        } else {
                            fail++
                        }
                    } catch (e: Throwable) {
                        fail++
                        Log.w(TAG, "补抓失败 ${item.mailNo}: ${e.message}")
                    }
                    delay(GAP_MS)
                }
                Log.i(TAG, "后台补抓完成 ok=$ok fail=$fail")
                // 卡片外显更新后刷新列表
                refreshList()
            } catch (e: Throwable) {
                Log.w(TAG, "backfill err: $e")
            } finally {
                running.set(false)
            }
        }
    }
}
