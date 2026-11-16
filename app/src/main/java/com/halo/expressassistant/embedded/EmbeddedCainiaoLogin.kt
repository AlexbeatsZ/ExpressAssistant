package com.halo.expressassistant.embedded

import android.app.Activity
import com.halo.expressassistant.App
import com.halo.expressassistant.api.CainiaoLoginLog
import com.halo.expressassistant.api.CainiaoMember
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.api.CainiaoServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Receives the embedded SDK's authenticated result directly, without Root or log capture. */
object EmbeddedCainiaoLogin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow("")
    private val awaitingLogin = AtomicBoolean(false)
    val state = mutableState.asStateFlow()

    fun login(activity: Activity) {
        try {
            awaitingLogin.set(true)
            mutableState.value = "请完成菜鸟登录"
            HostedCainiao.login()
        } catch (error: Exception) {
            android.util.Log.e("EA-CainiaoHost", "embedded_login_open_failed: " +
                generateSequence<Throwable>(error) { it.cause }.joinToString(" / ") { it.javaClass.simpleName })
            mutableState.value = "暂时无法打开登录，请稍后重试"
            android.widget.Toast.makeText(activity, mutableState.value, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @JvmStatic fun acceptSdkSession(raw: String, deviceRaw: String) {
        if (!awaitingLogin.get()) return
        scope.launch {
            try {
                val member = JSONObject(raw)
                CainiaoMember.validate(member)
                val device = JSONObject(deviceRaw)
                CainiaoMember.validateNative(device)
                val cookie = CainiaoMember.mergeCookies("", member)
                mutableState.value = "正在验证登录信息"
                CainiaoRenewal.acceptCapturedLogin(App.instance, CainiaoLoginLog.Candidate(member, device, cookie, true))
                awaitingLogin.set(false)
                mutableState.value = "菜鸟登录成功"
            } catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) { mutableState.value = "${e.kind.message}，请重试登录" }
            catch (_: Exception) { sessionUnavailable() }
        }
    }

    @JvmStatic fun sessionUnavailable() { mutableState.value = "登录信息尚未就绪，请重试登录" }
}
