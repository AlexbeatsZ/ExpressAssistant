package com.halo.expressassistant.ui

import com.halo.expressassistant.data.DetailPoint
import org.json.JSONObject

/** Read the current parcel's hydrated logistics model, never recommendation/order aggregates. */
object PddLogistics {
    data class Result(
        val points: List<DetailPoint> = emptyList(),
        val pickupCode: String = "",
        val pickupHint: String = "",
        val trackingNumber: String = "",
        val station: String = ""
    )

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()
    private fun code(value: String): String = value.takeIf {
        it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{1,19}")) && it.lowercase() !in setOf("null", "none", "true", "false", "needsms")
    }.orEmpty()

    fun parse(store: JSONObject): Result {
        val shipping = store.optJSONObject("traceData")?.optJSONObject("shipping") ?: return Result()
        val traces = shipping.optJSONArray("traces")
        val points = (0 until (traces?.length() ?: 0)).mapNotNull { index ->
            val trace = traces?.optJSONObject(index) ?: return@mapNotNull null
            val time = trace.text("time").ifBlank { trace.text("displayTime") }
            val info = trace.text("info")
            if (time.isBlank() || info.isBlank()) null else DetailPoint(
                listOf(trace.text("statusTag"), info).filter(String::isNotBlank).joinToString(" "), time, time)
        }
        val cabinet = store.optJSONObject("cabinetData")
        val inbound = cabinet?.optJSONObject("cnInOutBound")
        val hint = inbound?.text("display").orEmpty()
        val pickup = sequenceOf(
            shipping.text("pickCode"),
            shipping.optJSONObject("cabinetInfo")?.text("verifyCode").orEmpty(),
            cabinet?.text("pickupCode").orEmpty(),
            cabinet?.text("pickUpCode").orEmpty()
        ).map(::code).firstOrNull(String::isNotBlank)
            ?: GoodsPresentation.pickupCodeFrom(hint).orEmpty()
        return Result(points, pickup,
            hint.takeIf { it.contains("取件") && it.length <= 100 }.orEmpty(),
            shipping.text("trackingNumber"), inbound?.text("address").orEmpty())
    }
}
