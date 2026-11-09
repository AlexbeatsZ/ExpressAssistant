package com.halo.expressassistant

import com.halo.expressassistant.api.ManualPackages
import com.halo.expressassistant.data.*
import org.junit.Assert.*
import org.junit.Test

class ManualPackagesTest {
    private val item = ExpressItem("1234567890", "yunda", "韵达", "1234567890", source = "manual")
    @Test fun pastedWhitespaceDoesNotRequireAnAccount() {
        assertEquals("JT1234567890", ManualPackages.normalise(" jt12345\n67890\t"))
        assertTrue(ManualPackages.isWaybill("JT1234567890"))
        assertFalse(ManualPackages.isWaybill("买东西"))
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
