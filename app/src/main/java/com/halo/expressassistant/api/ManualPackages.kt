package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store

object ManualPackages {
    fun normalise(input: String) = input.trim().replace(Regex("\\s+"), "").uppercase()

    fun isWaybill(input: String): Boolean {
        val value = normalise(input)
        return value.matches(Regex("[A-Z0-9]{8,32}")) && value.count(Char::isDigit) >= 8
    }

    fun applyDetail(item: ExpressItem, detail: ExpressDetail): ExpressItem {
        if (detail.data.isEmpty() || item.isCompleted()) return item
        val newest = detail.data.maxByOrNull { it.formattedTime.ifBlank { it.time } } ?: return item
        val atDepot = com.halo.expressassistant.data.ParcelState.awaitingPickup(newest.context) &&
            !com.halo.expressassistant.data.ParcelState.collected(newest.context)
        val state = if (atDepot) 5 else if (detail.isReceived) 3 else detail.state
        return item.copy(
            latestText = newest.context, latestTime = newest.formattedTime.ifBlank { newest.time },
            state = state, stateNum = when (state) { 3 -> 107; 5 -> 105; 0, 1 -> 104; 4, 6 -> 108; else -> 0 },
            stateName = if (atDepot) "待取件" else when (state) { 3 -> "已签收"; 5 -> "派送中"; 0, 1 -> "运输中"; 4, 6 -> "已退回"; else -> "待更新" },
            receiptConfirmed = state == 3, pickupHint = if (state == 3) "" else item.pickupHint,
            eta = if (state == 3 || atDepot) "" else detail.eta, pickupCode = if (state == 3) "" else GoodsCode.find(newest.context)
        )
    }

    suspend fun refreshAll(ctx: Context, items: List<ExpressItem>): List<ExpressItem> = items.map { item ->
        if (item.source != "manual" || item.isCompleted() || item.orderOnly) item else try {
            val company = if (item.companyCode.isBlank()) KuaiDi100.detectCompany(item.mailNo) else null
            val resolved = if (company == null) item else item.copy(companyCode = company.comCode, companyName = company.name)
            val detail = KuaiDi100.fetchDetail(ctx, resolved)
            val fresh = applyDetail(resolved, detail)
            if (detail.data.isNotEmpty()) Store.saveManualTrace(ctx, item.mailNo, detail.data, fresh.isCompleted())
            fresh
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { item }
    }

    suspend fun refresh(ctx: Context, item: ExpressItem): ExpressDetail {
        val detail = KuaiDi100.fetchDetail(ctx, item)
        if (detail.data.isNotEmpty()) {
            val current = Store.items(ctx).firstOrNull { it.mailNo == item.mailNo } ?: return detail
            val fresh = applyDetail(current, detail)
            Store.saveManualTrace(ctx, item.mailNo, detail.data, fresh.isCompleted())
            Store.saveItems(ctx, Store.items(ctx).map { if (it.mailNo == item.mailNo) fresh else it })
        }
        return detail
    }

    private object GoodsCode {
        private val regex = Regex("(取件码|取货码|提货码|自提码|驿站码|取件验证码|取件密码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9-]{1,12})")
        fun find(text: String) = regex.find(text)?.groupValues?.get(2).orEmpty()
    }
}
