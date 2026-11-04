package com.halo.expressassistant.data

import com.halo.expressassistant.api.ManualPackages
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PackageStateTest {
    private fun parcel(source: String = "pdd", name: String = "", text: String = "", received: Boolean = false) =
        ExpressItem("sample", "PDD", "快递", "TEST12345678", source = source, stateName = name,
            latestText = text, receiptConfirmed = received)

    @Test fun receiptFieldsOverrideMarketing() {
        for (raw in listOf(
            """{"order_status_prompt":"拼过2次","shipping_status":2}""",
            """{"order_status_prompt":"拼过6次","receive_time":1780000000}"""
        )) {
            val status = PddOrderStatus.resolve(JSONObject(raw))
            assertEquals(3, status.state); assertEquals(107, status.number)
            assertEquals("已收货", status.label); assertTrue(status.received)
        }
    }

    @Test fun onlyExplicitDispatchIsDelivering() {
        val transit = PddOrderStatus.resolve(JSONObject("""{"order_status_prompt":"待收货","shipping_status":1}"""))
        assertEquals(0, transit.state); assertEquals(104, transit.number)
        val unknown = PddOrderStatus.resolve(JSONObject("""{"order_status_prompt":"拼过2次"}"""))
        assertEquals(-1, unknown.state); assertEquals(0, unknown.number)
        assertEquals("unknown", sectionKeyOf(parcel()))
        val dispatch = PddOrderStatus.resolve(JSONObject("""{"order_status_prompt":"快递正在派送"}"""))
        assertEquals(5, dispatch.state); assertEquals(105, dispatch.number)
    }

    @Test fun orderReceiptAndDepotReceiptAreDistinct() {
        assertTrue(parcel(name = "已提货").isCompleted())
        assertTrue(parcel(source = "jd", name = "处理成功").isCompleted())
        assertTrue(parcel(name = "拼过2次", received = true).isCompleted())
        assertFalse(parcel(text = "已签收，菜鸟驿站代收，请及时取件").isCompleted())
        assertFalse(parcel(name = "待签收").isCompleted())
        assertTrue(parcel(text = "您的快件已取走【菜鸟驿站】").isCompleted())
    }

    @Test fun waybillRecognitionDoesNotTreatKeywordsOrOrderIdsAsWaybills() {
        assertTrue(ManualPackages.isWaybill(" yt1234567890 "))
        assertFalse(ManualPackages.isWaybill("纸巾"))
        assertFalse(ManualPackages.isWaybill("1234"))
        assertFalse(ManualPackages.isWaybill("123456-1234567890"))
    }

    @Test fun cachedSignedTraceCorrectsFilledMarketingHeader() {
        val old = parcel(name = "拼过2次", text = "菜鸟驿站代收，请取件").copy(
            state = 5, stateNum = 105, latestTime = "2026-07-25 08:00", pickupCode = "old-code")
        val signed = DetailPoint("已签收，签收人凭取货码签收。", "2026-07-25 21:00")
        val depot = DetailPoint("菜鸟驿站代收，请及时取件", "2026-07-25 08:00")
        val fixed = ParcelState.withTrace(old, listOf(signed, depot))
        assertEquals("done", sectionKeyOf(fixed)); assertEquals(100, displayProgress(fixed))
        assertEquals("已签收", fixed.stateLabel()); assertEquals("", fixed.pickupCode)
        assertTrue(fixed.receiptConfirmed); assertEquals(signed.context, fixed.latestText)
    }

    @Test fun depotReceiptDoesNotCompleteParcelAndCannotUndoPriorReceipt() {
        val pending = parcel(name = "待收货").copy(state = 0, stateNum = 104, pickupCode = "1-2-3")
        val trace = listOf(DetailPoint("已签收，菜鸟驿站代收，请及时取件", "2026-07-25 08:00"))
        val fixed = ParcelState.withTrace(pending, trace)
        assertFalse(fixed.isCompleted()); assertEquals("1-2-3", fixed.pickupCode)
        assertTrue(ParcelState.withTrace(pending.copy(receiptConfirmed = true), trace).isCompleted())
    }
}
