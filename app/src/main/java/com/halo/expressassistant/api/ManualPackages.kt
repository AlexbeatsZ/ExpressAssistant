package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.CancellationException
import java.util.Locale

object ManualPackages {
    fun normalise(input: String) = input.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)

    fun isWaybill(input: String): Boolean {
        val value = normalise(input)
        return value.matches(Regex("[A-Z0-9]{8,32}")) && value.count(Char::isDigit) >= 8
    }

    fun matches(item: ExpressItem, waybill: String): Boolean =
        waybill.isNotEmpty() && (item.mailNo.equals(waybill, true) || item.trackingNumber.equals(waybill, true))

    fun add(ctx: Context, waybill: String, company: KuaiDi100.Company?): ExpressItem {
        val item = ExpressItem(id = waybill, mailNo = waybill, companyCode = company?.comCode.orEmpty(),
            companyName = company?.name.orEmpty().ifBlank { "快递" }, source = "manual", state = -1,
            stateNum = 0, stateName = "待查询")
        var result = item
        Store.updateItems(ctx) { current ->
            val existing = current.firstOrNull { matches(it, waybill) }
            if (existing != null) {
                result = existing
                current
            } else {
                Store.restoreHidden(ctx, waybill)
                listOf(item) + current
            }
        }
        return result
    }

    /** A sync may finish after a manual addition or a newer detail lookup. */
    fun retainManualEdits(
        before: Map<String, ExpressItem>, synced: List<ExpressItem>, current: List<ExpressItem>
    ): List<ExpressItem> {
        val edits = current.filter { it.source == "manual" && it != before[it.mailNo] }
        val updated = synced.map { item ->
            if (item.source != "manual") item else edits.firstOrNull { it.mailNo == item.mailNo }
                ?.takeUnless { item.isCompleted() && !it.isCompleted() } ?: item
        }
        return edits.filter { edit -> updated.none { matches(it, edit.mailNo) } } + updated
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
            eta = if (state == 3 || atDepot) "" else detail.eta,
            pickupCode = if (state == 3 || state in setOf(4, 6)) "" else GoodsCode.find(newest.context)
                .ifBlank { item.pickupCode.takeIf { atDepot }.orEmpty() }
        )
    }

    suspend fun refreshAll(ctx: Context, items: List<ExpressItem>): List<ExpressItem> = items.map { item ->
        if (item.source != "manual" || item.isCompleted() || item.orderOnly) item else try {
            val (fresh, detail) = lookup(ctx, item)
            if (detail.data.isNotEmpty()) Store.saveManualTrace(ctx, item.mailNo, detail.data, fresh.isCompleted())
            fresh
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { item }
    }

    suspend fun refresh(ctx: Context, item: ExpressItem): ExpressDetail {
        val (resolved, detail) = lookup(ctx, item)
        Store.updateItems(ctx) { current ->
            current.map {
                if (it.source != "manual" || it.mailNo != item.mailNo) it else {
                    if (it.isCompleted() && !resolved.isCompleted()) it else {
                        Store.saveManualTrace(ctx, item.mailNo, detail.data, resolved.isCompleted())
                        resolved.copy(tracked = it.tracked, notifiedText = it.notifiedText, notifiedTime = it.notifiedTime,
                            addressId = it.addressId, stateOverride = it.stateOverride, partitionOverride = it.partitionOverride)
                    }
                }
            }
        }
        return detail
    }

    private suspend fun lookup(ctx: Context, item: ExpressItem): Pair<ExpressItem, ExpressDetail> {
        val now = System.currentTimeMillis()
        val canQueryCainiao = runCatching { CainiaoRenewal.snapshot(ctx).canAutoRenew }.getOrDefault(false)
        var pickupStatus = if (canQueryCainiao) "" else "no_session"
        if (canQueryCainiao && !item.isCompleted()) {
            try {
                val fetchPickup = item.pickupCode.isBlank() && now - item.pickupQueriedAt >= 3600_000
                val response = CainiaoRenewal.queryParcel(ctx, item.mailNo, includePickup = fetchPickup)
                CainiaoParcelDetail.parse(item, response)?.let { (fresh, detail) ->
                    val updated = if (fetchPickup && fresh.isAwaitingPickup()) fresh.copy(pickupQueriedAt = now) else fresh
                    if (detail.data.isNotEmpty() || fresh.isCompleted() || fresh.isAwaitingPickup() || fresh.state == 4)
                        return updated to detail
                }
            } catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) { pickupStatus = e.kind.name.lowercase(Locale.ROOT) }
        }
        val company = if (item.companyCode.isBlank()) KuaiDi100.detectCompany(item.mailNo) else null
        val resolved = company?.let { item.copy(companyCode = it.comCode, companyName = it.name.ifBlank { item.companyName }) } ?: item
        val detail = KuaiDi100.fetchDetail(ctx, resolved)
        val updated = applyDetail(resolved, detail)
        return if (pickupStatus.isNotBlank() && updated.pickupCode.isBlank())
            updated.copy(pickupQueryStatus = pickupStatus) to detail else updated to detail
    }

    private object GoodsCode {
        private val regex = Regex("(取件码|取货码|提货码|自提码|驿站码|取件验证码|取件密码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9-]{1,12})")
        fun find(text: String) = regex.find(text)?.groupValues?.get(2).orEmpty()
    }
}
