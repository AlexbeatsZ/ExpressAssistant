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
        val eta = EtaParser.extract(detail.data.joinToString(" ") { it.context })
        if (eta.isNotBlank()) result = result.copy(eta = eta)
        return result
    }
}
