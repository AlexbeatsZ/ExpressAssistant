package com.halo.expressassistant

import com.halo.expressassistant.api.CainiaoMember
import com.halo.expressassistant.api.CainiaoServiceException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CainiaoMemberTest {
    private val now = 1_800_000_000_000L
    private fun member(id: Long = 1234, sessionExpiry: Long = now + 86_400_000) = JSONObject()
        .put("sessionId", "test-session").put("refreshToken", "test-refresh").put("cnAccountId", id)
        .put("tenantId", 78).put("ext", JSONObject().put("sessionIdExpired", sessionExpiry)
            .put("refreshTokenExpired", now + 30L * 86_400_000).put("loginCookie", JSONArray()
                .put("cnMobileSid=fresh-sid; Domain=.cainiao.com; Path=/; Expires=Wed, 01 Jan 2042 00:00:00 GMT")
                .put("FROM_TYPE=cainiao; Domain=.cainiao.com; Path=/")))

    @Test fun expiredSessionRemainsRenewableWithValidRefreshToken() {
        val expired = member(sessionExpiry = now - 1)
        assertTrue(CainiaoMember.canRenew(expired, now))
        assertTrue(CainiaoMember.needsRenewal(expired, now))
        assertFalse(CainiaoMember.canRenew(expired, now + 31L * 86_400_000))
        assertFalse(CainiaoMember.needsRenewal(member(), now))
        assertTrue(CainiaoMember.needsRenewal(member(sessionExpiry = now + CainiaoMember.RENEW_BEFORE_MS), now))
    }

    @Test fun requestIncludesInheritedConsumerFieldsAndStringEncodedPayload() {
        val device = JSONObject().put("utdid", "test-device").put("ttid", "test@android")
        val request = CainiaoMember.request(member(), device, "6.0.3.2")
        assertTrue(request.get("autoLoginRequest") is String)
        assertFalse(request.has("loginRequest"))
        val payload = JSONObject(request.getString("autoLoginRequest"))
        assertEquals(1234, payload.getLong("accountId"))
        assertEquals("test-refresh", payload.getString("refreshToken"))
        assertEquals("23050477", payload.getString("appKey"))
        assertEquals(78, payload.getInt("tenantId"))
        assertEquals("6.0.3.2", payload.getString("sdkVersion"))
        assertEquals("test-device", payload.getString("utdid"))
    }

    @Test fun twelveHourWorkerRenewsBeforeTheOneDaySessionExpires() {
        val value = member(sessionExpiry = now + 24L * 60 * 60 * 1000)
        assertFalse(CainiaoMember.needsRenewal(value, now))
        assertTrue(CainiaoMember.needsRenewal(value, now + 12L * 60 * 60 * 1000))
    }

    @Test fun acceptsRotatedTokensButRejectsAccountSubstitutionAndExpiredResponses() {
        val before = member()
        val next = member().put("sessionId", "rotated-session").put("refreshToken", "rotated-refresh")
        fun response(value: JSONObject) = JSONObject().put("data", JSONObject().put("data", value))
        val accepted = CainiaoMember.accept(response(next), before, now)
        assertEquals("rotated-refresh", accepted.getString("refreshToken"))
        assertEquals("test-refresh", before.getString("refreshToken"))
        assertThrows(IllegalArgumentException::class.java) { CainiaoMember.accept(response(member(5678)), before, now) }
        assertThrows(IllegalArgumentException::class.java) { CainiaoMember.accept(response(member(sessionExpiry = now)), before, now) }
        assertThrows(IllegalArgumentException::class.java) { CainiaoMember.accept(response(next.put("tenantId", 21)), before, now) }
    }

    @Test fun replacesSessionCookieWithoutLosingH5TokensOrSplittingExpiresComma() {
        val cookie = CainiaoMember.mergeCookies("cnMobileSid=stale; _m_h5_tk=h5-token; retained=a=b", member())
        assertEquals(1, Regex("cnMobileSid=").findAll(cookie).count())
        assertTrue(cookie.contains("cnMobileSid=fresh-sid"))
        assertTrue(cookie.contains("_m_h5_tk=h5-token"))
        assertTrue(cookie.contains("retained=a=b"))
        assertFalse(cookie.contains("Expires="))
    }

    @Test fun rejectsForeignCookieDomainAndMissingIdentityCookie() {
        val value = member()
        value.getJSONObject("ext").put("loginCookie", JSONArray().put("cnMobileSid=bad; Domain=.example.com; Path=/"))
        assertThrows(IllegalArgumentException::class.java) { CainiaoMember.mergeCookies("retained=yes", value) }
        value.getJSONObject("ext").put("loginCookie", JSONArray().put("other=x; Domain=.cainiao.com; Path=/"))
        assertThrows(IllegalArgumentException::class.java) { CainiaoMember.mergeCookies("retained=yes", value) }
    }

    @Test fun terminalMemberErrorsRequireLoginAndDoNotEchoServerText() {
        val error = CainiaoServiceException.fromCodes(listOf("FAIL_BIZ_401001::test-private-data"))
        assertEquals(CainiaoServiceException.Kind.LOGIN_EXPIRED, error.kind)
        assertFalse(error.message!!.contains("test-private-data"))
        assertEquals(CainiaoServiceException.Kind.REJECTED, CainiaoServiceException.fromCodes(listOf("FAIL_BIZ_7000003")).kind)
    }
}
