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

    @Test fun unrelatedTextStillWorksAsLocalSearchButDoesNotMatchWaybillRules() {
        assertFalse(WaybillRules.isValid("快递公司"))
        assertFalse(WaybillRules.isValid("13800138000"))
        assertFalse(WaybillRules.isValid("123456 789012"))
    }
}
