package com.halo.expressassistant.data

/** Reconcile durable receipt evidence before any list, header or API renders a parcel. */
object ParcelState {
    private val depotHints = listOf("驿站", "快递柜", "代收", "待取件", "丰巢", "自提点", "暂存")

    fun collected(text: String): Boolean {
        if (listOf("已取走", "已完成取件", "快件领取成功", "已提货", "已取货").any(text::contains)) return true
        return text.contains("已签收") && depotHints.none(text::contains)
    }

    fun withTrace(item: ExpressItem, points: List<DetailPoint>): ExpressItem {
        val last = points.maxByOrNull { it.formattedTime.ifBlank { it.time } }
        val received = item.isCompleted() || last?.let { collected(it.context) } == true
        val latestTime = last?.formattedTime?.ifBlank { last.time }.orEmpty()
        val result = if (last != null && (item.latestTime.isBlank() || latestTime >= item.latestTime)) item.copy(
            latestText = last.context.take(220).ifBlank { item.latestText },
            latestTime = latestTime.ifBlank { item.latestTime }
        ) else item
        return if (received) result.copy(state = 3, stateNum = 107, receiptConfirmed = true, pickupCode = "",
            stateName = if (PddOrderStatus.completedLabel(result.stateName)) result.stateName else "已签收") else result
    }
}
