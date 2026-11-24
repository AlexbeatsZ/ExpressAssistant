package com.halo.expressassistant

import com.halo.expressassistant.api.CainiaoLoginLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CainiaoLoginLogTest {
    private val now = 1_800_000_000_000L
    private val memberLine = "other login prefix|SessionInfo(refreshToken=refresh, tenantId=78, cnAccountId=1234, " +
        "sessionId=sid, ext={refreshTokenExpired=${now + 200_000}, sessionIdExpired=${now + 100_000}, extra="
    private fun headers(account: String = "1234") = "request headers: {x-utdid=test%2Bdevice, x-uid=$account, " +
        "Cookie=cnMobileSid=cookie; _m_h5_tk=token_123, x-ttid=test%40android}"

    @Test fun readsMemberFieldsWithoutAssumingPrefixOrderOrAClosingBracket() {
        val member = CainiaoLoginLog.parseMember(memberLine, now)!!
        assertEquals("refresh", member.getString("refreshToken"))
        assertEquals("1234", member.getString("cnAccountId"))
        assertNull(CainiaoLoginLog.parseMember(memberLine.replace("tenantId=78", "tenantId=21"), now))
        assertNull(CainiaoLoginLog.parseMember(memberLine.replace("refreshToken=refresh", "refreshToken=null"), now))
    }

    @Test fun waitsForMatchingRequestContextAndKeepsCapturedMemberInMemory() {
        val parser = CainiaoLoginLog()
        assertNull(parser.accept(memberLine, null, now))
        assertNull(parser.accept(headers("5678"), null, now))
        val candidate = parser.accept(headers(), null, now)!!
        assertTrue(candidate.hasCapturedMember)
        assertEquals("test+device", candidate.device.getString("utdid"))
        assertEquals("test@android", candidate.device.getString("ttid"))
        assertEquals("cookie", candidate.cookie.substringAfter("cnMobileSid=").substringBefore(';'))
    }

    @Test fun supportsRequestArrivingBeforeTheLoginRecord() {
        val parser = CainiaoLoginLog()
        assertNull(parser.accept(headers(), null, now))
        assertTrue(parser.accept(memberLine, null, now)!!.hasCapturedMember)
    }

    @Test fun cookieRecoveryReusesSavedMemberOnlyForTheSameAccountAndOriginalDevice() {
        val existing = JSONObject().put("member", CainiaoLoginLog.parseMember(memberLine, now))
            .put("native", JSONObject().put("utdid", "original-device").put("ttid", "original@android"))
        val parser = CainiaoLoginLog()
        assertNull(parser.accept(headers("5678"), existing, now))
        val candidate = parser.accept(headers(), existing, now)!!
        assertFalse(candidate.hasCapturedMember)
        assertEquals("original-device", candidate.device.getString("utdid"))
        assertNull(CainiaoLoginLog().accept(headers(), existing, now + 300_000))
    }
}
