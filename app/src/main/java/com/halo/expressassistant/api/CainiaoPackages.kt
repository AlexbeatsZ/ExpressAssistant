package com.halo.expressassistant.api

import com.halo.expressassistant.data.BoundAccount
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Parcel queries use the Taobao H5 login, independently of the identity-code session. */
object CainiaoPackages {
    private val terminal = setOf("SIGN", "CANCEL", "REJECT", "RETURN", "LOST", "FAILED")
    private val placeholders = setOf("needsms", "need_sms", "null", "none", "0", "-1", "true", "false")

    fun pickupCode(pkg: JSONObject): String {
        if (pkg.optString("logisticsStatus").uppercase() in terminal) return ""
        var code = pkg.optJSONObject("packageStation")?.optString("authCode").orEmpty().trim()
        if (code.equals("needSms", true)) {
            val feature = pkg.optJSONObject("featureObj")?.takeIf { it.length() > 0 }
                ?: runCatching { JSONObject(pkg.optString("feature", "{}")) }.getOrNull()
            code = feature?.optString("stationOrderPickUpCode").orEmpty().trim()
        }
        return code.takeUnless { it.lowercase() in placeholders }.orEmpty()
    }

    fun toItem(pkg: JSONObject, account: BoundAccount): ExpressItem {
        val status = pkg.optString("logisticsStatus").uppercase()
        val state = when {
            status == "SIGN" -> 3
            status in terminal -> 4
            status.contains("STA") || status.contains("DELIVER") || status in setOf("SENT_SCAN", "SENT_CITY") -> 5
            status in setOf("CREATE", "CONSIGN", "WAIT_ACCEPT") -> 1
            else -> 0
        }
        val mail = pkg.getString("mailNo")
        return ExpressItem(
            id = mail, mailNo = mail, companyCode = pkg.optString("partnerCode"),
            companyName = pkg.optString("partnerName", "菜鸟包裹"),
            latestText = pkg.optString("lastLogisticDetail").ifBlank { pkg.optString("logisticsStatusDesc") },
            latestTime = pkg.optString("logisticsGmtModified").take(19), state = state,
            stateNum = when (state) { 3 -> 107; 4 -> 108; 5 -> 105; 1 -> 103; else -> 104 },
            stateName = pkg.optString("logisticsStatusDesc"), provider = "CaiNiao", source = "cainiao",
            pickupCode = pickupCode(pkg), station = pkg.optJSONObject("packageStation")?.optString("stationName").orEmpty(),
            accountId = account.id, accountLabel = account.label
        )
    }

    fun fetch(account: BoundAccount): List<ExpressItem> {
        val http = H5Mtop(Store.cookieOf(account.payload))
        var lastError: Exception? = null
        for (api in listOf("mtop.cainiao.lpc.packageservice.queryPdsPackageDataList", "mtop.cainiao.pcs.packageservice.queryPackageDataList")) {
            val items = linkedMapOf<String, ExpressItem>()
            try {
                for (page in 1..20) {
                    val result = http.call(api, JSONObject().put("pageSize", 50).put("currentPage", page)
                        .put("needTemporality", false).put("needDynInfo", "{'needFindTemporality':true,'needAppointment':true}"))
                    val data = result.optJSONObject("data") ?: result
                    val packages = data.optJSONArray("packages")
                    for (i in 0 until (packages?.length() ?: 0)) {
                        val pkg = packages!!.optJSONObject(i) ?: continue
                        if (pkg.optString("mailNo").isNotBlank()) toItem(pkg, account).let { items[it.mailNo] = it }
                    }
                    if (packages == null || packages.length() == 0 || page >= data.optInt("totalPage", 1)) return items.values.toList()
                    check(page < 20) { "菜鸟包裹页数超出本次同步上限，已保留旧数据" }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastError = e }
        }
        throw lastError ?: IllegalStateException("菜鸟包裹读取失败")
    }

    /** Successful blank codes and terminal states must clear obsolete pickup credentials. */
    fun merge(existing: List<ExpressItem>, packages: List<ExpressItem>): List<ExpressItem> {
        val merged = existing.associateByTo(linkedMapOf()) { it.mailNo }
        for (pkg in packages) {
            val old = merged[pkg.mailNo] ?: merged.values.firstOrNull { it.trackingNumber == pkg.mailNo }
            if (old != null) {
                val base = if (old.source == "cainiao") pkg.copy(tracked = old.tracked, addressId = old.addressId,
                    stateOverride = old.stateOverride, partitionOverride = old.partitionOverride) else old
                merged[old.mailNo] = when {
                    old.isCompleted() -> old.copy(pickupCode = "", pickupHint = "", eta = "", receiptConfirmed = true)
                    pkg.isCompleted() -> CainiaoParcelSync.apply(base, CainiaoPickup.Result(status = "completed")).copy(
                        latestText = pkg.latestText.ifBlank { "已签收" }, latestTime = pkg.latestTime.ifBlank { old.latestTime })
                    pkg.state == 4 || pkg.stateNum in 108..111 -> base.copy(state = pkg.state, stateNum = pkg.stateNum,
                        stateName = pkg.stateName, pickupCode = "", pickupHint = "", eta = "",
                        latestText = pkg.latestText, latestTime = pkg.latestTime)
                    else -> base.copy(pickupCode = if (old.state == 4 || old.stateNum in 108..111) "" else pkg.pickupCode,
                        station = pkg.station.ifBlank { old.station })
                }
            } else if (pkg.state !in setOf(3, 4)) merged[pkg.mailNo] = pkg
        }
        return merged.values.toList()
    }
}
