package com.halo.expressassistant.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.expressassistant.R
import com.halo.expressassistant.data.AccountLoginStore
import com.halo.expressassistant.data.BoundAccount
import com.halo.expressassistant.data.LoginStatus
import com.halo.expressassistant.data.Store
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginStatusIntegrationTest {
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 4000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "login-previews").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun isolated(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "login-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(key: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { block(context) } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun reloginPreservesRenamedAccountsAndRejectsOldFailures() = isolated { ctx ->
        Store.addTbAccount(ctx, "unb=123456789; cookie1=old")
        val first = Store.accounts(ctx, Store.CH_TAOBAO).single()
        Store.updateAccount(ctx, Store.CH_TAOBAO, first.copy(label = "我的淘宝"))
        AccountLoginStore.record(ctx, Store.CH_TAOBAO, first, false, "淘宝登录已失效")
        assertEquals(listOf(Store.CH_TAOBAO), AccountLoginStore.expiredChannels(ctx))
        Store.addTbAccount(ctx, "unb=123456789; cookie1=new")
        val fresh = Store.accounts(ctx, Store.CH_TAOBAO).single()
        assertEquals(first.id, fresh.id)
        assertEquals("我的淘宝", fresh.label)
        assertTrue(fresh.loginGeneration > first.loginGeneration)
        AccountLoginStore.record(ctx, Store.CH_TAOBAO, first, false, "淘宝登录已失效")
        assertEquals(LoginStatus.VALID, AccountLoginStore.status(ctx, Store.CH_TAOBAO, fresh.id))
        assertTrue(AccountLoginStore.expiredChannels(ctx).isEmpty())
    }

    @Test fun expiryIsPerAccountAndSurvivesNetworkFailureAndCookieRotation() = isolated { ctx ->
        Store.addAccount(ctx, Store.CH_JD, BoundAccount("a", payload = Store.cookiePayload("pt_pin=one; pt_key=old")))
        Store.addAccount(ctx, Store.CH_JD, BoundAccount("b", payload = Store.cookiePayload("pt_pin=two; pt_key=old")))
        val a = Store.accounts(ctx, Store.CH_JD).first()
        val b = Store.accounts(ctx, Store.CH_JD).last()
        AccountLoginStore.record(ctx, Store.CH_JD, a, false, "京东需要重新登录验证")
        AccountLoginStore.record(ctx, Store.CH_JD, b, true, null)
        AccountLoginStore.record(ctx, Store.CH_JD, a, false, "连接超时")
        assertEquals(LoginStatus.EXPIRED, AccountLoginStore.status(ctx, Store.CH_JD, a.id))
        assertEquals(LoginStatus.VALID, AccountLoginStore.status(ctx, Store.CH_JD, b.id))
        val rotated = Store.updateAccountPayload(ctx, Store.CH_JD, a, Store.cookiePayload("pt_pin=one; pt_key=rotated"))!!
        assertEquals(LoginStatus.EXPIRED, AccountLoginStore.status(ctx, Store.CH_JD, a.id))
        AccountLoginStore.record(ctx, Store.CH_JD, rotated, true, null)
        assertTrue(AccountLoginStore.expiredChannels(ctx).isEmpty())
        AccountLoginStore.record(ctx, Store.CH_JD, rotated, false, "京东需要重新登录验证")
        Store.updateAccount(ctx, Store.CH_JD, rotated.copy(enabled = false))
        assertTrue(AccountLoginStore.expiredChannels(ctx).isEmpty())
        assertEquals(LoginStatus.EXPIRED, AccountLoginStore.status(ctx, Store.CH_JD, a.id))
    }

    @Test fun homeWarningSurvivesActivityRecreationAndClearsOnVerifiedLogin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        // This test runs only on the isolated development emulator.
        val original = Store.accounts(ctx, Store.CH_TAOBAO)
        val id = "login-ui-test"
        try {
            Store.addAccount(ctx, Store.CH_TAOBAO, BoundAccount(id, "示例淘宝", payload = Store.cookiePayload("unb=preview")))
            val account = Store.accountById(ctx, Store.CH_TAOBAO, id)!!
            AccountLoginStore.record(ctx, Store.CH_TAOBAO, account, false, "淘宝登录已失效")
            Store.setLastAutoSync(ctx, System.currentTimeMillis())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun assertWarning(visible: Boolean) = scenario.onActivity { activity ->
                    assertEquals(if (visible) View.VISIBLE else View.GONE, activity.findViewById<View>(R.id.login_warning_panel).visibility)
                    if (visible) assertTrue(activity.findViewById<TextView>(R.id.login_warning_summary).text.contains("淘宝"))
                }
                assertWarning(true)
                screenshot("home")
                scenario.recreate()
                assertWarning(true)
            }
            ActivityScenario.launch<SettingsActivity>(Intent(ctx, SettingsActivity::class.java)).use { settings ->
                    settings.onActivity { activity ->
                        assertTrue(activity.findViewById<TextView>(R.id.tb_login_summary).text.contains("登录已失效"))
                }
                    screenshot("settings")
            }
            ActivityScenario.launch<SettingsActivity>(Intent(ctx, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_LOGIN_CHANNEL, Store.CH_TAOBAO)).use { settings ->
                    instrumentation.waitForIdleSync()
                    screenshot("account")
                    assertTrue(instrumentation.uiAutomation.rootInActiveWindow
                        .findAccessibilityNodeInfosByText("重新登录").isNotEmpty())
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.login_warning_panel).visibility)
                    AccountLoginStore.signedIn(activity, Store.CH_TAOBAO, id)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.login_warning_panel).visibility)
                }
            }
        } finally { Store.saveAccounts(ctx, Store.CH_TAOBAO, original) }
    }
}
