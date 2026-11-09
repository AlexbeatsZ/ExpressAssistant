package com.halo.expressassistant.api

import org.json.JSONObject

/** Strictly associate credentials with the requested waybill, never another parcel in the response. */
object CainiaoPickup {
    data class Result(val code: String = "", val status: String = "unavailable", val stationId: String = "", val station: String = "")
    private fun text(value: JSONObject?, key: String): String =
        if (value == null || value.isNull(key)) "" else value.optString(key).trim()
    private fun validCode(value: String): String = value.takeIf {
        it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{1,19}")) &&
            it.lowercase() !in setOf("null", "none", "needsms", "true", "false", "0", "-1")
    }.orEmpty()

    fun parse(response: JSONObject, mailNo: String): Result {
        val data = response.optJSONObject("data") ?: return Result()
        val global = data.optJSONObject("globalData") ?: return Result()
        if (text(global, "mailNo") != mailNo) return Result(status = "mismatch")
        val stationId = text(global, "stationId")
        var station = ""
        var directCode = ""
        val body = data.optJSONObject("uiProtocolData")?.optJSONArray("body")
        for (i in 0 until (body?.length() ?: 0)) {
            val groups = body?.optJSONObject(i)?.optJSONArray("group") ?: continue
            for (j in 0 until groups.length()) {
                val group = groups.optJSONObject(j) ?: continue
                val fields = group.optJSONObject("data") ?: continue
                when (group.optString("id")) {
                    "guoguo_logistic_card_base_info" -> station = text(fields.optJSONObject("topText"), "text")
                    "guoguo_logistic_card_important_info" -> if (text(fields.optJSONObject("mainText"), "text") == "取件码") {
                        directCode = validCode(text(fields.optJSONObject("subText"), "text"))
                    }
                }
            }
        }
        val state = text(global.optJSONObject("formerModel")?.optJSONObject("status"), "statusCode")
        if (state in setOf("SIGN", "SIGNED")) return Result(status = "completed", stationId = stationId, station = station)
        if (state in setOf("CANCEL", "RETURN", "REJECT", "LOST", "FAILED")) return Result(status = "abnormal", stationId = stationId, station = station)
        if (directCode.isNotBlank()) return Result(directCode, "ok", stationId, station)
        val auth = response.optJSONObject("pickupAuth")?.optJSONObject("data")
        if (auth != null && (!auth.optBoolean("identityCodeCertificated") || !auth.optBoolean("authToGetPickupCode")))
            return Result(status = "authorization_required", stationId = stationId, station = station)
        val verified = response.optJSONObject("pickupResult")?.optJSONObject("data")
        if (verified?.optBoolean("verifyResult") == true) {
            val packages = verified.optJSONArray("packageDTOList")
            for (i in 0 until (packages?.length() ?: 0)) {
                val pkg = packages?.optJSONObject(i) ?: continue
                if (text(pkg, "mailNo") != mailNo || text(pkg, "stationId").let { it.isNotBlank() && it != stationId }) continue
                val code = validCode(text(pkg, "pickupCode"))
                if (code.isNotBlank()) return Result(code, "ok", stationId, station)
            }
        }
        return Result(status = if (verified != null) "verification_required" else "unavailable", stationId = stationId, station = station)
    }
}
