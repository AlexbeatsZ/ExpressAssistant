package com.halo.expressassistant

import com.halo.expressassistant.api.CainiaoSessionAccess
import com.halo.expressassistant.api.CainiaoServiceException
import org.junit.Assert.*
import org.junit.Test

class CainiaoSessionAccessTest {
    @Test fun validCookieNeverNeedsNativeRuntime() {
        assertEquals("online", CainiaoSessionAccess.read({ "online" }, { error("Native runtime must not be loaded") }))
    }
    @Test fun expiredCookieRenewsAndRetriesOnce() {
        var requests = 0; var renewals = 0
        val result = CainiaoSessionAccess.read({
            if (++requests == 1) throw CainiaoServiceException(CainiaoServiceException.Kind.LOGIN_EXPIRED)
            "fresh"
        }, { renewals++; true })
        assertEquals("fresh", result); assertEquals(2, requests); assertEquals(1, renewals)
    }
    @Test fun networkFailureDoesNotRotateCredentials() {
        val error = CainiaoServiceException(CainiaoServiceException.Kind.NETWORK)
        try { CainiaoSessionAccess.read({ throw error }, { fail("Unexpected renewal"); true }) }
        catch (e: CainiaoServiceException) { assertSame(error, e); return }
        fail("Expected failure")
    }
    @Test fun unsuccessfulRenewalKeepsExpiryError() {
        val error = CainiaoServiceException(CainiaoServiceException.Kind.LOGIN_EXPIRED)
        try { CainiaoSessionAccess.read({ throw error }, { false }) }
        catch (e: CainiaoServiceException) { assertSame(error, e); return }
        fail("Expected expiry")
    }
    @Test fun repeatedExpiryDoesNotLoop() {
        var requests = 0; var renewals = 0
        try { CainiaoSessionAccess.read({ requests++; throw CainiaoServiceException(CainiaoServiceException.Kind.LOGIN_EXPIRED) }, { renewals++; true }) }
        catch (_: CainiaoServiceException) { assertEquals(2, requests); assertEquals(1, renewals); return }
        fail("Expected expiry")
    }
}
