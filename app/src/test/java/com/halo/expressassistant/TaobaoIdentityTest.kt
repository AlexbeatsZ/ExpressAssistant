package com.halo.expressassistant

import com.halo.expressassistant.api.TaobaoIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaobaoIdentityTest {
    @Test fun preservesTheIssuedValueAndLeadingZeroes() {
        val code = TaobaoIdentity.parse(JSONObject().put("result", JSONObject().put("identityCode", "001234567890123456")), 1000)
        assertEquals("001234567890123456", code.value)
        assertTrue(code.usable(1000))
        assertTrue(code.usable(180_999))
        assertFalse(code.usable(181_000))
        assertFalse(code.usable(999))
    }

    @Test fun acceptsTheOtherOfficialResponseWrapperWithoutSubstitutingGuoguo() {
        val code = TaobaoIdentity.parse(JSONObject().put("data", JSONObject().put("identityCode", "XNS12345678901234567")), 1000)
        assertEquals("XNS12345678901234567", code.value)
        assertEquals(181_000L, code.refreshAtMs)
    }

    @Test fun emptyOrMissingCodeDoesNotBecomeABarcode() {
        for (data in listOf(JSONObject(), JSONObject().put("result", JSONObject().put("identityCode", "")),
            JSONObject().put("result", JSONObject().put("success", false)))) {
            val error = assertThrows(TaobaoIdentity.Failure::class.java) { TaobaoIdentity.parse(data) }
            assertEquals(TaobaoIdentity.Error.UNAVAILABLE, error.error)
        }
    }

    @Test fun rejectsControlCharactersAndUnboundedBarcodeValues() {
        for (value in listOf("123456\n7890", "123456\u0000790", "a".repeat(81))) {
            val error = assertThrows(TaobaoIdentity.Failure::class.java) {
                TaobaoIdentity.parse(JSONObject().put("identityCode", value))
            }
            assertEquals(TaobaoIdentity.Error.INVALID_RESPONSE, error.error)
        }
    }
}
