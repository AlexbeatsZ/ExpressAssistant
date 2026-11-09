package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.ExpressItem
import kotlinx.coroutines.CancellationException

/** Native parcel state is independent of the Taobao H5 session and pickup-code lookup quota. */
object CainiaoParcelSync {
    fun waybill(item: ExpressItem): String = item.trackingNumber.ifBlank {
        if (item.source in setOf("taobao", "cainiao", "manual")) item.mailNo else ""
    }

    fun apply(item: ExpressItem, result: CainiaoPickup.Result): ExpressItem {
        if (item.isCompleted()) return item.copy(receiptConfirmed = true, pickupCode = "", pickupHint = "", eta = "")
        return when (result.status) {
            "completed" -> item.copy(state = 3, stateNum = 107, stateName = "已签收", receiptConfirmed = true,
                pickupCode = "", pickupHint = "", eta = "", latestText = "已签收")
            "abnormal" -> item.copy(state = 4, stateNum = 108, stateName = "物流异常", pickupCode = "", pickupHint = "", eta = "")
            else -> item
        }
    }

    suspend fun refresh(ctx: Context, items: List<ExpressItem>): List<ExpressItem> {
        if (!CainiaoRenewal.snapshot(ctx).canAutoRenew) return items
        return items.map { item ->
            val mail = waybill(item)
            if (!item.isAwaitingPickup() || mail.isBlank()) return@map item
            try {
                val response = CainiaoRenewal.queryParcel(ctx, mail, includePickup = false)
                apply(item, CainiaoPickup.parse(response, mail))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { item }
        }
    }
}
