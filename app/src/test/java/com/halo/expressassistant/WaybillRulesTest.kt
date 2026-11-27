package com.halo.expressassistant

import com.halo.expressassistant.api.WaybillRules
import org.junit.Assert.*
import org.junit.Test

class WaybillRulesTest {
    @Test fun validatesLengthAndCharacterCounts() {
        assertTrue(WaybillRules.isValid("SF1234567890123"))
        assertTrue(WaybillRules.isValid("ABCDEF123456"))
        assertTrue(WaybillRules.isValid("12345678901234567"))
        assertFalse(WaybillRules.isValid("12345678901"))
        assertFalse(WaybillRules.isValid("123456789012345678"))
        assertFalse(WaybillRules.isValid("ABCDEFG123456"))
        assertFalse(WaybillRules.isValid("12345678901_"))
        assertFalse(WaybillRules.isValid("123456-1234567890"))
        assertFalse(WaybillRules.isValid("１２３４５６７８９０１２"))
    }

    @Test fun countsHyphensAsNonDigits() {
        assertTrue(WaybillRules.isValid("SF-12345678901"))
        assertFalse(WaybillRules.isValid("AB-CD-EF123456"))
    }

    @Test fun extractsAllValidCandidatesAndDeduplicatesIgnoringCase() {
        assertEquals(listOf("SF1234567890123", "123456789012"),
            WaybillRules.extract("运单sf1234567890123，123456789012\nsf1234567890123"))
    }

    @Test fun rejectsEntireLongTokenInsteadOfImportingASubstring() {
        assertEquals(emptyList<String>(), WaybillRules.extract("123456789012345678901234567890123456"))
        assertEquals(listOf("123456789012"), WaybillRules.extract("123456789012345678，123456789012"))
    }

    @Test fun smsRequiresDeliveryContext() {
        assertEquals(emptyList<String>(), WaybillRules.extract("账户变动123456789012", fromSms = true))
        assertEquals(listOf("123456789012"), WaybillRules.extract("【中通快递】您的运单123456789012已到驿站", fromSms = true))
    }

    @Test fun skipsOrderNumbersPhonesAndCodesInDeliverySms() {
        assertEquals(listOf("SF1234567890123"), WaybillRules.extract(
            "订单号为：123456789012，快递运单号SF1234567890123，取件码123-456-7890，验证码123456789013，手机号13800138000",
            fromSms = true
        ))
    }

    @Test fun skipsNumbersAndIdentifiersInLinks() {
        assertEquals(listOf("123456789012"), WaybillRules.extract(
            "快递123456789012，查询 https://example.com/987654321012?a=SF1234567890123", fromSms = true
        ))
    }

    @Test fun smsExtractsStandaloneCodesFromLabelsAndPickupInstructions() {
        assertEquals(listOf("1-2-3456", "A012"), WaybillRules.smsCandidates(
            "【驿站】取件码为【１－２－３４５６】，另一个包裹请凭 a012 到前台取件，开柜码 A012"
        ).pickupCodes.map { it.uppercase(java.util.Locale.ROOT) })
        val longCode = WaybillRules.smsCandidates("您的取件码是123456789012，请及时取件")
        assertTrue(longCode.waybills.isEmpty())
        assertEquals(listOf("123456789012"), longCode.pickupCodes)
    }

    @Test fun smsPreservesWaybillPathAndIgnoresUnrelatedOrMalformedCodes() {
        val withWaybill = WaybillRules.smsCandidates("快递运单SF1234567890123已到站，取件码1234")
        assertEquals(listOf("SF1234567890123"), withWaybill.waybills)
        assertTrue(withWaybill.pickupCodes.isEmpty())
        listOf("【银行】验证码123456", "验证码1234用于确认快递服务", "凭1234登录账号",
            "取件码12345678901234567890", "查看 https://example.com/取件码1234").forEach {
            assertTrue(it, WaybillRules.smsCandidates(it).pickupCodes.isEmpty())
        }
    }

    @Test fun unrelatedTextStillWorksAsLocalSearchButDoesNotMatchWaybillRules() {
        assertFalse(WaybillRules.isValid("快递公司"))
        assertFalse(WaybillRules.isValid("13800138000"))
        assertFalse(WaybillRules.isValid("123456 789012"))
    }
}
