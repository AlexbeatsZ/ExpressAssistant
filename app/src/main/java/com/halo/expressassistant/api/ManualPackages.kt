package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store

object ManualPackages {
    fun normalise(input: String) = input.trim().replace(" ", "").uppercase()

    fun isWaybill(input: String): Boolean {
        val value = normalise(input)
        return value.matches(Regex("[A-Z0-9]{8,32}")) && value.count(Char::isDigit) >= 8
    }

    fun applyDetail(item: ExpressItem, detail: ExpressDetail): ExpressItem {
        if (detail.data.isEmpty()) return item
        val newest = detail.data.maxByOrNull { it.formattedTime.ifBlank { it.time } } ?: return item
        val state = if (detail.isReceived) 3 else detail.state
        return item.copy(
            latestText = newest.context, latestTime = newest.formattedTime.ifBlank { newest.time },
            state = state, stateNum = when (state) { 3 -> 107; 5 -> 105; 0, 1 -> 104; 4, 6 -> 108; else -> 0 },
            stateName = when (state) { 3 -> "已签收"; 5 -> "派送中"; 0, 1 -> "运输中"; 4, 6 -> "已退回"; else -> "待更新" },
            eta = detail.eta, pickupCode = if (state == 3) "" else GoodsCode.find(newest.context)
        )
    }

    suspend fun refresh(ctx: Context, item: ExpressItem): ExpressDetail {
        val detail = KuaiDi100.fetchDetail(ctx, item)
        if (detail.data.isNotEmpty()) {
            Store.saveManualTrace(ctx, item.mailNo, detail.data, detail.isReceived || detail.state == 3)
            val fresh = applyDetail(item, detail)
            Store.saveItems(ctx, Store.items(ctx).map { if (it.mailNo == item.mailNo) fresh else it })
        }
        return detail
    }

    private object GoodsCode {
        private val regex = Regex("(取件码|取货码|提货码|自提码|驿站码|取件验证码|取件密码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9-]{1,12})")
        fun find(text: String) = regex.find(text)?.groupValues?.get(2).orEmpty()
    }
}
