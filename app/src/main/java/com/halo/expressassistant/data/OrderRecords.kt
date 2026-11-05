package com.halo.expressassistant.data

/** 物流页没有运单时仍保存已付款订单，出现正式运单后替换同账号的占位记录。 */
object OrderRecords {
    fun key(source: String, accountId: String, orderId: String): String = "$source|$accountId|$orderId"
    fun key(item: ExpressItem): String = key(item.source, item.accountId, item.queryChannel)

    fun mergeHistory(fresh: List<ExpressItem>, old: List<ExpressItem>, observed: Set<String> = emptySet()): List<ExpressItem> {
        val union = (fresh + old.filterNot { it.orderOnly && key(it) in observed }).distinctBy { it.mailNo }
        val withWaybill = union.filter { !it.orderOnly && it.queryChannel.isNotBlank() }.mapTo(HashSet(), ::key)
        return union.filterNot { it.orderOnly && key(it) in withWaybill }
    }
}
