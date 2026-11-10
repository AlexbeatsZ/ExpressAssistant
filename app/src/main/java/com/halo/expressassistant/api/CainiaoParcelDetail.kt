package com.halo.expressassistant.api

import com.halo.expressassistant.data.DetailPoint
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Extract the same waybill's logistics feed and pickup state from the native detail response. */
internal object CainiaoParcelDetail {
    private val fullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val datePattern = Regex("""(\d{2})\.(\d{2}) (\d{2}):(\d{2})""")

    fun parse(item: ExpressItem, response: JSONObject, now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))):
        Pair<ExpressItem, ExpressDetail>? {
        val data = response.optJSONObject("data") ?: return null
        val pickup = CainiaoPickup.parse(response, item.mailNo)
        if (pickup.status == "mismatch") return null
        val global = data.optJSONObject("globalData") ?: return null
        val former = global.optJSONObject("formerModel")
        val status = former?.optJSONObject("status")
        val statusCode = CainiaoPickup.text(status, "statusCode")
        var company = item.companyName
        val points = ArrayList<DetailPoint>()
        var latest = now
        for (group in CainiaoPickup.groups(data)) {
            val fields = group.optJSONObject("data") ?: continue
            when (group.optString("id")) {
                "guoguo_logistic_cp_info" -> {
                    val label = CainiaoPickup.text(fields.optJSONObject("contentText"), "text")
                    val name = label.substringBefore(item.mailNo, "").trim().trimEnd('：', ':', '·').trim()
                    if (name.isNotBlank()) company = name
                }
                "guoguo_logistic_feed_info" -> {
                    val description = CainiaoPickup.text(fields.optJSONObject("bottomSubText"), "text")
                        .ifBlank { CainiaoPickup.text(fields.optJSONObject("bottomMainText"), "text") }
                    val time = CainiaoPickup.text(fields.optJSONObject("topText"), "text")
                    if (description.isBlank()) continue
                    val parsed = datePattern.matchEntire(time)?.destructured?.let { (month, day, hour, minute) ->
                        runCatching {
                            val date = MonthDay.of(month.toInt(), day.toInt())
                            val candidate = date.atYear(latest.year).atTime(hour.toInt(), minute.toInt())
                            if (candidate.isAfter(latest.plusDays(1))) candidate.minusYears(1) else candidate
                        }.getOrNull()
                    }
                    if (parsed != null) latest = parsed
                    points.add(DetailPoint(description, time, parsed?.format(fullDate) ?: time))
                }
            }
        }
        val statusLabel = CainiaoPickup.text(status, "statusDesc")
        if (points.isEmpty() && statusCode.isBlank() && pickup.code.isBlank()) return null
        val atDepot = pickup.code.isNotBlank() || statusCode in setOf("AGENT_SIGN", "STA_INBOUND") ||
            com.halo.expressassistant.data.ParcelState.awaitingPickup(points.firstOrNull()?.context.orEmpty())
        val state = when {
            pickup.status == "completed" -> 3
            pickup.status == "abnormal" -> 4
            atDepot || statusCode in setOf("DELIVERING", "DELIVER", "DISPATCH") -> 5
            statusCode in setOf("CREATE", "CONSIGN", "WAIT_ACCEPT") -> 1
            points.isNotEmpty() -> 0
            else -> -1
        }
        val detail = ExpressDetail(item.mailNo, company, state, state == 3, points)
        val traced = ManualPackages.applyDetail(item.copy(companyName = company), detail)
        if (state == 3) return CainiaoParcelSync.apply(traced, pickup).copy(
            latestText = if (points.isEmpty()) statusLabel.ifBlank { "已签收" } else traced.latestText
        ) to detail
        val code = if (state == 4) "" else pickup.code.ifBlank { traced.pickupCode.takeIf { atDepot }.orEmpty() }
        val updated = if (traced.isCompleted()) traced else traced.copy(
            state = state,
            stateNum = when (state) { 0 -> 104; 1 -> 103; 4 -> 108; 5 -> 105; else -> 0 },
            stateName = if (atDepot && state != 4) "待取件" else statusLabel.ifBlank { traced.stateName },
            pickupCode = code,
            pickupCodeSource = if (pickup.code.isNotBlank()) "cainiao" else traced.pickupCodeSource,
            station = pickup.station.ifBlank { traced.station },
            pickupQueryStatus = if (code.isNotBlank()) "ok" else pickup.status,
            latestText = traced.latestText.ifBlank { statusLabel },
            eta = if (atDepot) "" else traced.eta
        )
        return updated to detail
    }
}
