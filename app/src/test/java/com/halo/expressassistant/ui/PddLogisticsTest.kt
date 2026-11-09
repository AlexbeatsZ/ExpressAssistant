package com.halo.expressassistant.ui

import com.halo.expressassistant.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PddLogisticsTest {
    private val item = ExpressItem("sample", "PDD", "快递", "ORDER-123", source = "pdd", state = 0, stateNum = 104, eta = "今天送达")
    private fun model(code: String = "null") = JSONObject("""{
        "traceData":{"shipping":{"trackingNumber":"JT0000012345","pickCode":$code,
            "traces":[{"info":"快件已送达菜鸟驿站暂存，请及时取件","time":"2026-11-08 17:22:10","statusTag":"待取件"},
            {"info":"预计今天送达，正在派件","time":"2026-11-08 13:52:37","statusTag":"派件中"},
            {"info":"快件已揽收","time":"2026-11-07 10:00:00"}]}},
        "cabinetData":{"cnInOutBound":{"display":"取件出示快递单号后五位 12345","address":"测试驿站"}},
        "goodsList":[{"pickupCode":"DO-NOT-USE"}]
    }""")

    @Test fun readsFullHydratedTracesAndKeepsSuffixSeparateFromCode() {
        val result = PddLogistics.parse(model())
        assertEquals(3, result.points.size)
        assertEquals("", result.pickupCode)
        assertEquals("取件出示快递单号后五位 12345", result.pickupHint)
        assertEquals("JT0000012345", result.trackingNumber)
        assertEquals("测试驿站", result.station)
    }

    @Test fun actualPickupCodeRemainsAnActualCode() {
        assertEquals("8-2-1234", PddLogistics.parse(model("\"8-2-1234\"")).pickupCode)
        assertEquals("", PddLogistics.parse(JSONObject("""{"goodsList":[{"pickupCode":"BAD"}]}""")).pickupCode)
    }

    @Test fun arrivalSuppressesOldForecastAndMisleadingProgress() {
        val result = PddLogistics.parse(model())
        val fixed = DetailState.reconcile(item, ExpressDetail(item.mailNo, "快递", 0, false, result.points))
        assertEquals("待取件", fixed.stateLabel())
        assertEquals("pickup", sectionKeyOf(fixed))
        assertEquals("", fixed.eta)
        assertEquals("", fixed.etaLabel())
        assertEquals(-1, displayProgress(fixed))
        assertFalse(fixed.isCompleted())
        assertEquals(fixed, DetailState.reconcile(fixed, ExpressDetail(item.mailNo, "快递", 0, false, result.points.sortedBy { it.time })))
    }

    @Test fun collectedParcelClearsEveryPickupCredential() {
        val fixed = ParcelState.withTrace(item.copy(pickupHint = "取件出示快递单号后五位 12345"),
            listOf(DetailPoint("快件已取走【菜鸟驿站】", "2026-11-09 10:00:00")))
        assertTrue(fixed.isCompleted())
        assertEquals("", fixed.pickupLabel())
        assertEquals("", fixed.pickupHint)
        assertEquals("", fixed.eta)
    }

    @Test fun transitEtaExplicitlyMeansForecast() {
        assertEquals("预计今天送达", item.etaLabel())
        assertEquals("预计11月10日送达", item.copy(eta = "预计11月10日送达").etaLabel())
        assertFalse(item.copy(latestText = "预计明天送达菜鸟驿站").isAwaitingPickup())
    }

    @Test fun codeInAnotherTraceIsNotLostWhenLastRowIsOldest() {
        val points = listOf(DetailPoint("快件已到驿站，取件码：8-2-1234", "2026-11-08 17:00:00"),
            DetailPoint("快件运输中", "2026-11-07 12:00:00"))
        val fixed = DetailState.reconcile(item, ExpressDetail(item.mailNo, "快递", 0, false, points))
        assertEquals("8-2-1234", fixed.pickupCode)
        assertEquals("待取件", fixed.stateLabel())
    }
}
