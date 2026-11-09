package com.halo.expressassistant

import com.halo.expressassistant.api.CainiaoPickup
import com.halo.expressassistant.api.CainiaoPackages
import com.halo.expressassistant.data.ExpressItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CainiaoPickupTest {
    private fun response() = JSONObject("""{
      "data":{"globalData":{"mailNo":"JT0000012345","stationId":"station-1","formerModel":{"status":{"statusCode":"AGENT_SIGN"}}}},
      "pickupAuth":{"data":{"identityCodeCertificated":"true","authToGetPickupCode":"true"}},
      "pickupResult":{"data":{"verifyResult":"true","packageDTOList":[
        {"mailNo":"OTHER","stationId":"station-1","pickupCode":"wrong-code"},
        {"mailNo":"JT0000012345","stationId":"station-1","pickupCode":"6-4-1234"}]}}
    }""")
    @Test fun extractsOnlyVerifiedCodeForRequestedWaybill() {
        val result = CainiaoPickup.parse(response(), "JT0000012345")
        assertEquals("6-4-1234", result.code)
        assertEquals("ok", result.status)
    }
    @Test fun rejectsUnrelatedResponseAndWrongStation() {
        assertEquals("mismatch", CainiaoPickup.parse(response(), "OTHER").status)
        val r = response()
        r.getJSONObject("pickupResult").getJSONObject("data").getJSONArray("packageDTOList").getJSONObject(1).put("stationId", "other-station")
        assertEquals("", CainiaoPickup.parse(r, "JT0000012345").code)
    }
    @Test fun noCodeWithoutVerificationOrAuthorization() {
        val r = response()
        r.getJSONObject("pickupResult").getJSONObject("data").put("verifyResult", false)
        assertEquals("verification_required", CainiaoPickup.parse(r, "JT0000012345").status)
        r.getJSONObject("pickupAuth").getJSONObject("data").put("authToGetPickupCode", false)
        assertEquals("authorization_required", CainiaoPickup.parse(r, "JT0000012345").status)
    }
    @Test fun rejectsMaskedOrPlaceholderCodes() {
        for (code in listOf("****", "needSms", "null", "0")) {
            val r = response()
            r.getJSONObject("pickupResult").getJSONObject("data").getJSONArray("packageDTOList").getJSONObject(1).put("pickupCode", code)
            assertEquals("", CainiaoPickup.parse(r, "JT0000012345").code)
        }
    }
    @Test fun completedParcelDoesNotRestoreCredential() {
        val r = response()
        r.getJSONObject("data").getJSONObject("globalData").getJSONObject("formerModel").getJSONObject("status").put("statusCode", "SIGN")
        assertEquals("completed", CainiaoPickup.parse(r, "JT0000012345").status)
        assertEquals("", CainiaoPickup.parse(r, "JT0000012345").code)
    }
    @Test fun cainiaoListMatchesPddWaybillWithoutDuplicatingOrder() {
        val pdd = ExpressItem("order", "PDD", "拼多多", "order-key", source = "pdd", state = 0, trackingNumber = "JT0000012345")
        val cn = ExpressItem("waybill", "HTKY", "极兔", "JT0000012345", source = "cainiao", state = 5, pickupCode = "6-4-1234")
        val merged = CainiaoPackages.merge(listOf(pdd), listOf(cn))
        assertEquals(1, merged.size)
        assertEquals("order-key", merged.single().mailNo)
        assertEquals("6-4-1234", merged.single().pickupCode)
        assertEquals("", CainiaoPackages.merge(merged, listOf(cn.copy(pickupCode = ""))).single().pickupCode)
    }
    @Test fun signedListUpdatesOriginalAndCannotRegress() {
        val old = ExpressItem("id", "", "韵达", "1234567890", source = "taobao", state = 0, stateName = "待取件", pickupCode = "1-2-3")
        val signed = old.copy(source = "cainiao", state = 3, stateNum = 107, stateName = "已签收", pickupCode = "")
        val done = CainiaoPackages.merge(listOf(old), listOf(signed)).single()
        assertTrue(done.receiptConfirmed)
        assertEquals("已签收", done.stateLabel())
        assertEquals("", done.pickupCode)
        assertTrue(CainiaoPackages.merge(listOf(done), listOf(old.copy(source = "cainiao"))).single().isCompleted())
    }
    @Test fun depotAcceptanceAndReturnsAreNotUserCollection() {
        for (state in listOf("AGENT_SIGN", "ACCEPTED", "RETURN", "CANCEL", "REJECT")) {
            val r = response()
            r.getJSONObject("data").getJSONObject("globalData").getJSONObject("formerModel").getJSONObject("status").put("statusCode", state)
            val result = CainiaoPickup.parse(r, "JT0000012345")
            assertNotEquals("completed", result.status)
        }
    }
    @Test fun nativeReceiptClearsHintsAndUsesRealWaybill() {
        val old = ExpressItem("order", "PDD", "极兔", "order-key", source = "pdd", state = 0,
            trackingNumber = "JT0000012345", pickupCode = "1-2-3", pickupHint = "末五位12345", eta = "今天送达")
        assertEquals("JT0000012345", com.halo.expressassistant.api.CainiaoParcelSync.waybill(old))
        val done = com.halo.expressassistant.api.CainiaoParcelSync.apply(old, CainiaoPickup.Result(status = "completed"))
        assertTrue(done.isCompleted())
        assertFalse(done.isAwaitingPickup())
        assertEquals("", done.pickupCode + done.pickupHint + done.eta)
        assertEquals("order-key", done.mailNo)
    }
}
