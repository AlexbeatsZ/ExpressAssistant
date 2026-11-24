package com.halo.expressassistant.data

import org.junit.Assert.*
import org.junit.Test

class LoginStatusTest {
    @Test fun explicitAuthenticationFailurePersistsUntilVerifiedRecovery() {
        assertEquals(LoginStatus.EXPIRED, LoginStatus.after(LoginStatus.UNKNOWN, false, "淘宝登录已失效，请在设置中重新登录"))
        assertEquals(LoginStatus.EXPIRED, LoginStatus.after(LoginStatus.VALID, false, "京东需要重新登录验证，已保留旧数据"))
        assertEquals(LoginStatus.EXPIRED, LoginStatus.after(LoginStatus.EXPIRED, false, "网络超时"))
        assertEquals(LoginStatus.VALID, LoginStatus.after(LoginStatus.EXPIRED, true, null))
    }

    @Test fun emptyOrPartialResponsesDoNotProveAuthentication() {
        assertEquals(LoginStatus.UNKNOWN, LoginStatus.after(LoginStatus.UNKNOWN, false, null))
        assertEquals(LoginStatus.EXPIRED, LoginStatus.after(LoginStatus.EXPIRED, true, "列表超时（已用部分数据）"))
        for (error in listOf("登录接口连接超时", "淘宝订单读取失败，请在设置中检查登录状态", "FAIL_SYS_TOKEN_EXOIRED", "FAIL_SYS_TOKEN_EMPTY", "HTTP 503"))
            assertFalse(error, LoginStatus.isExpiredError(error))
    }

    @Test fun staleResultsCannotAffectReloggedRemovedOrDisabledAccounts() {
        val requested = BoundAccount("one", payload = "old-session")
        assertTrue(LoginStatus.accepts(requested.copy(label = "renamed"), requested))
        assertFalse(LoginStatus.accepts(requested.copy(payload = "new-session"), requested))
        assertFalse(LoginStatus.accepts(requested.copy(id = "another"), requested))
        assertFalse(LoginStatus.accepts(requested.copy(loginGeneration = 1L), requested))
        assertFalse(LoginStatus.accepts(requested.copy(enabled = false), requested))
        assertFalse(LoginStatus.accepts(null, requested))
    }
}
