package com.halo.expressassistant

import com.halo.expressassistant.api.ManualPackages
import com.halo.expressassistant.api.CainiaoParcelDetail
import com.halo.expressassistant.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ManualPackagesTest {
    private val item = ExpressItem("1234567890", "yunda", "韵达", "1234567890", source = "manual")
    @Test fun pastedWhitespaceDoesNotRequireAnAccount() {
        assertEquals("JT1234567890", ManualPackages.normalise(" jt12345\n67890\t"))
        assertEquals("JT1234567890", ManualPackages.normalise("jt12345\u00a067890"))
        assertTrue(ManualPackages.isWaybill("JT1234567890"))
        assertFalse(ManualPackages.isWaybill("买东西"))
    }
    @Test fun syncPreservesManualPackageAddedAfterSnapshot() {
        val existing = item.copy(mailNo = "PDD-ORDER", source = "pdd")
        assertEquals(listOf(item, existing),
            ManualPackages.retainManualEdits(mapOf(existing.mailNo to existing), listOf(existing), listOf(item, existing)))
    }
    @Test fun concurrentManualAdditionDoesNotDuplicateAnOrderWithTheSameWaybill() {
        val order = item.copy(mailNo = "PDD-ORDER", trackingNumber = item.mailNo, source = "pdd")
        assertEquals(listOf(order), ManualPackages.retainManualEdits(emptyMap(), listOf(order), listOf(item)))
    }
    @Test fun nativeDetailReturnsPickupAndFeedForTheRequestedWaybill() {
        val response = JSONObject("""
            {"data":{"globalData":{"mailNo":"1234567890","stationId":"test",
              "formerModel":{"status":{"statusCode":"AGENT_SIGN","statusDesc":"驿站代收"}}},
              "uiProtocolData":{"body":[{"group":[
                {"id":"guoguo_logistic_card_important_info","data":{"mainText":{"text":"取件码"},"subText":{"text":"****"}}},
                {"id":"guoguo_logistic_feed_info","data":{"topText":{"text":"01.02 10:00"},"bottomSubText":{"text":"已由驿站代收"}}},
                {"id":"guoguo_logistic_feed_info","data":{"topText":{"text":"12.31 10:00"},"bottomSubText":{"text":"已发货"}}}
              ]}]}},
              "pickupResult":{"data":{"verifyResult":true,"packageDTOList":[
                {"mailNo":"9876543210","stationId":"test","pickupCode":"9-9-9"},
                {"mailNo":"1234567890","stationId":"test","pickupCode":"1-2-3"}
              ]}}}
        """.trimIndent())
        val (fresh, detail) = CainiaoParcelDetail.parse(item, response, LocalDateTime.of(2026, 1, 2, 11, 0))!!
        assertEquals("1-2-3", fresh.pickupCode)
        assertTrue(fresh.isAwaitingPickup())
        assertEquals(listOf("2026-01-02 10:00:00", "2025-12-31 10:00:00"), detail.data.map { it.formattedTime })
        assertNull(CainiaoParcelDetail.parse(item.copy(mailNo = "9876543210"), response))
    }
    @Test fun depotReceiptIsStillAwaitingPickup() {
        val detail = ExpressDetail(item.mailNo, item.companyName, 3, true,
            listOf(DetailPoint("已由菜鸟驿站代收，请及时取件", "2026-11-09 10:00", "2026-11-09 10:00")))
        val result = ManualPackages.applyDetail(item, detail)
        assertFalse(result.isCompleted())
        assertTrue(result.isAwaitingPickup())
        assertEquals("待取件", result.stateLabel())
    }
    @Test fun collectionIsDurableAndClearsCredentials() {
        val detail = ExpressDetail(item.mailNo, item.companyName, 3, true,
            listOf(DetailPoint("本人已签收", "2026-11-09 11:00", "2026-11-09 11:00")))
        val done = ManualPackages.applyDetail(item.copy(pickupCode = "1-2-3", pickupHint = "末五位"), detail)
        assertTrue(done.receiptConfirmed)
        assertEquals("", done.pickupCode + done.pickupHint)
        assertEquals(done, ManualPackages.applyDetail(done, detail.copy(state = 0, isReceived = false)))
    }
}
