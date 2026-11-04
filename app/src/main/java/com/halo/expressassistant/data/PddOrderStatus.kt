package com.halo.expressassistant.data

import org.json.JSONObject

object PddOrderStatus {
    data class Resolved(val state: Int, val number: Int, val label: String, val received: Boolean)
    private val completed = listOf("已签收", "已收货", "已提货", "已取货", "已取走", "交易成功", "已完成", "待评价", "已评价")
    fun completedLabel(label: String) = completed.any { label.startsWith(it) }

    fun resolve(order: JSONObject): Resolved {
        val prompt = order.optString("order_status_prompt").ifBlank {
            listOf("order_status_text", "status_prompt_text", "order_status_desc", "combined_order_status_prompt", "status_desc").firstNotNullOfOrNull {
                order.optString(it).takeIf(String::isNotBlank)
            }.orEmpty()
        }.trim()
        val received = order.optInt("shipping_status", -1) == 2 || order.optLong("receive_time", 0) > 0
        val meaningful = prompt.takeUnless { it.matches(Regex("拼过\\d+次")) }.orEmpty()
        if (received || completedLabel(meaningful)) return Resolved(3, 107, meaningful.ifBlank { "已收货" }, true)
        if (listOf("派送", "派件", "正在配送").any { meaningful.contains(it) }) return Resolved(5, 105, meaningful, false)
        if (listOf("待发货", "未发货", "待成团").any { meaningful.contains(it) }) return Resolved(1, 103, meaningful, false)
        if (order.optInt("shipping_status", -1) == 1 || listOf("待收货", "等待收货", "已发货", "运输", "揽收").any { meaningful.contains(it) }) {
            return Resolved(0, 104, meaningful.ifBlank { "运输中" }, false)
        }
        return Resolved(-1, 0, meaningful.ifBlank { "待更新" }, false)
    }
}
