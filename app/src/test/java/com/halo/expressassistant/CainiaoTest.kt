package com.halo.expressassistant

import com.halo.expressassistant.api.CainiaoIdentity
import com.halo.expressassistant.api.CainiaoPackages
import com.halo.expressassistant.api.CainiaoServiceException
import com.halo.expressassistant.data.BoundAccount
import com.halo.expressassistant.data.ExpressItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CainiaoTest {
    private val material = CainiaoIdentity.Material(
        Base64.getEncoder().encodeToString("test-only-key-1234567890".toByteArray()),
        123456789, 6, 600, 2_000_000_000_000L, 'A'
    )

    @Test fun matchesIndependentPythonVectorsAndTimeBoundary() {
        assertEquals("CS80760497535836773A", CainiaoIdentity.generate(material, 0))
        assertEquals("CS80760497535836773A", CainiaoIdentity.generate(material, 599999))
        assertEquals("CS80579597443183827A", CainiaoIdentity.generate(material, 600000))
        assertEquals("CS80171610739837875A", CainiaoIdentity.generate(material, 1793796954000))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsExpiredMaterial() {
        CainiaoIdentity.generate(material, material.expiresAtMs)
    }

    @Test fun onlineCodeTakesPriorityAndExpiresIntoOfflineCode() {
        val online = CainiaoIdentity.OnlineCode("CS81234567890123456A", 1000)
        val current = CainiaoIdentity.display(material, online, 999)!!
        assertTrue(current.online); assertEquals(online.value, current.value)
        val fallback = CainiaoIdentity.display(material, online, 1000)!!
        assertFalse(fallback.online); assertEquals(CainiaoIdentity.generate(material, 1000), fallback.value)
        assertEquals(600000, fallback.expiresAtMs)
    }

    @Test fun offlineCountdownUsesActualPeriodAndDoesNotChangeCodeOnButtonTime() {
        val first = CainiaoIdentity.display(material, null, 600001)!!
        val next = CainiaoIdentity.display(material, null, 601000)!!
        assertEquals(first.value, next.value)
        assertEquals(1200000, first.expiresAtMs)
        assertNotEquals(first.value, CainiaoIdentity.display(material, null, 1200000)!!.value)
    }

    @Test fun onlineExpiryIsSecondsFromReceiptAndCannotBeMaterialTimestamp() {
        val model = JSONObject().put("certificated", true).put("identityCode", "CS81234567890123456A").put("expireTime", 120)
        assertEquals(121000, CainiaoIdentity.parseOnline(model, 1000).expiresAtMs)
        assertThrows(IllegalArgumentException::class.java) {
            CainiaoIdentity.parseOnline(model.put("expireTime", 1795000000000L), 1000)
        }
    }

    @Test fun expiredLoginHasActionableErrorWithoutEchoingPayload() {
        val error = CainiaoServiceException.fromCodes(listOf("FAIL_SYS_SESSION_EXPIRED::secret-cookie-do-not-echo"))
        assertEquals(CainiaoServiceException.Kind.LOGIN_EXPIRED, error.kind)
        assertTrue(error.message!!.contains("重新登录"))
        assertFalse(error.message!!.contains("secret-cookie"))
        assertEquals(CainiaoServiceException.Kind.REJECTED,
            CainiaoServiceException.fromCodes(listOf("FAIL_SYS_TOKEN_EXPIRED")).kind)
    }

    @Test fun expiredOfflineMaterialCanStillUseValidOnlineCode() {
        val expired = material.copy(expiresAtMs = 100)
        assertNull(CainiaoIdentity.display(expired, null, 100))
        assertTrue(CainiaoIdentity.display(expired, CainiaoIdentity.OnlineCode("CS81234567890123456A", 200), 100)!!.online)
    }

    @Test fun pickupRequiresActualCodeAndHonorsTerminalState() {
        fun pkg(auth: String) = JSONObject().put("packageStation", JSONObject().put("authCode", auth))
        assertEquals("8-2-123", CainiaoPackages.pickupCode(pkg("8-2-123")))
        assertEquals("", CainiaoPackages.pickupCode(pkg("needSms")))
        assertEquals("A1234", CainiaoPackages.pickupCode(pkg("needSms").put("feature", "{\"stationOrderPickUpCode\":\"A1234\"}")))
        assertEquals("", CainiaoPackages.pickupCode(pkg("8-2-123").put("logisticsStatus", "SIGN")))
        // A feature value without the needSms sentinel is not authoritative.
        assertEquals("", CainiaoPackages.pickupCode(pkg("").put("featureObj", JSONObject().put("stationOrderPickUpCode", "old"))))
    }

    @Test fun successfulBlankClearsOldCodeAndAdditionalTerminalPackagesStayOut() {
        val old = ExpressItem("m", "", "圆通", "m", state = 5, pickupCode = "old")
        val current = old.copy(source = "cainiao", pickupCode = "", station = "驿站")
        val done = current.copy(id = "done", mailNo = "done", state = 3)
        val merged = CainiaoPackages.merge(listOf(old), listOf(current, done))
        assertEquals(1, merged.size)
        assertEquals("", merged.single().pickupCode)
        assertEquals("驿站", merged.single().station)
        assertEquals("", CainiaoPackages.merge(listOf(old), listOf(current.copy(state = 3, pickupCode = "invalid"))).single().pickupCode)
    }

    @Test fun terminalAndFeatureObjConversion() {
        val pkg = JSONObject("{\"mailNo\":\"m\",\"logisticsStatus\":\"WAIT_DELIVER\",\"packageStation\":{\"authCode\":\"needSms\"},\"featureObj\":{\"stationOrderPickUpCode\":\"3-2-1\"}}")
        val item = CainiaoPackages.toItem(pkg, BoundAccount("test"))
        assertEquals("3-2-1", item.pickupCode)
        assertEquals(5, item.state)
        assertEquals("cainiao", item.source)
    }

    @Test fun pickedUpPddParcelDoesNotBecomePendingBecauseItMentionsStation() {
        val base = ExpressItem("p", "", "", "p", source = "pdd", state = 0, stateNum = 105)
        assertTrue(base.copy(latestText = "您的快件已取走【菜鸟驿站】").isCompleted())
        assertTrue(base.copy(latestText = "已签收，签收人凭取货码签收。").isCompleted())
        assertFalse(base.copy(latestText = "已签收，菜鸟驿站代收，请及时取件").isCompleted())
    }
}
