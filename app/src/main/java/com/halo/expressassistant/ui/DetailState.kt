package com.halo.expressassistant.ui

import com.halo.expressassistant.api.EtaParser
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.ParcelState

/** Reconcile all detail-derived fields before saving or displaying a snapshot. */
object DetailState {
    fun reconcile(item: ExpressItem, detail: ExpressDetail): ExpressItem {
        var result = ParcelState.withTrace(item, detail.data)
        if (!result.isCompleted() && result.pickupCode.isBlank()) {
            val code = GoodsPresentation.pickupCodeFromParts(detail.data.map { it.context } + result.latestText)
            if (!code.isNullOrBlank()) result = result.copy(pickupCode = code)
        }
        // Historical dispatch forecasts must never override arrival/receipt evidence.
        val newest = detail.data.maxByOrNull { it.formattedTime.ifBlank { it.time } }
        val eta = if (result.isCompleted() || result.isAwaitingPickup()) "" else EtaParser.extract(newest?.context)
        if (eta.isNotBlank()) result = result.copy(eta = eta)
        return if (result.isCompleted() || result.isAwaitingPickup()) result.copy(eta = "") else result
    }
}
