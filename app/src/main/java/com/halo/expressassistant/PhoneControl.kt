package com.halo.expressassistant

import android.content.Context
import com.halo.expressassistant.api.CainiaoIdentity
import com.halo.expressassistant.api.CainiaoMember
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.api.CainiaoServiceException
import com.halo.expressassistant.data.CainiaoSessionStore
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.ui.SyncEngine
import com.halo.expressassistant.ui.UpdateChecker
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated loopback maintenance API, part of the same APK; not a resident server. */
object PhoneControl {
    const val PORT = 18768
    private var server: ControlServer? = null

    @Synchronized fun token(ctx: Context): String {
        val path = File(ctx.noBackupFilesDir, "phone-control.token")
        if (path.exists()) return path.readText().trim()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        path.writeText(token)
        return token
    }

    @Synchronized fun start(ctx: Context) {
        if (server != null) return
        try {
            ControlServer(ctx.applicationContext, token(ctx)).also { it.start(30_000, true); server = it }
        } catch (_: Exception) {
            android.util.Log.w("PhoneControl", "Local management listener could not start")
        }
    }

    fun configuration(ctx: Context): String = JSONObject()
        .put("baseUrl", "http://127.0.0.1:$PORT").put("token", token(ctx))
        .put("transport", "phone loopback; adb forward for desktop access").toString(2)

    private class ControlServer(private val ctx: Context, private val secret: String) : NanoHTTPD("127.0.0.1", PORT) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val busy = AtomicBoolean(false)
        @Volatile private var syncState = JSONObject().put("running", false)

        private fun json(value: Any, status: Response.Status = Response.Status.OK): Response =
            newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()).apply {
                addHeader("Cache-Control", "no-store")
            }

        private fun error(status: Response.Status, message: String) = json(JSONObject().put("ok", false).put("error", message), status)

        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.removeSuffix("/")
            if (path == "/api/health" && session.method == Method.GET) {
                return json(JSONObject().put("ok", true).put("app", "express-assistant-phone").put("version", UpdateChecker.versionName(ctx)))
            }
            val provided = session.headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
            if (!MessageDigest.isEqual(provided.toByteArray(), secret.toByteArray())) return error(Response.Status.UNAUTHORIZED, "unauthorized")
            return try {
                when {
                    path == "/api/status" && session.method == Method.GET -> json(status())
                    path == "/api/setup" && session.method == Method.POST -> {
                        if (busy.get()) return error(Response.Status.CONFLICT, "sync running; retry after completion")
                        val raw = body(session)
                        val message = runBlocking { CainiaoRenewal.importConfiguration(ctx, raw) }
                        notifyUi()
                        json(status().put("message", message))
                    }
                    path == "/api/identity/refresh" && session.method == Method.POST -> {
                        if (runBlocking { CainiaoRenewal.refresh(ctx, force = true) } == null)
                            throw CainiaoServiceException(CainiaoServiceException.Kind.NO_SESSION)
                        notifyUi()
                        json(status())
                    }
                    path == "/api/identity/online/refresh" && session.method == Method.POST -> {
                        runBlocking { CainiaoRenewal.online(ctx, force = true) }
                        notifyUi()
                        json(status())
                    }
                    path == "/api/session/refresh" && session.method == Method.POST -> {
                        runBlocking { CainiaoRenewal.renewSession(ctx) }
                        notifyUi()
                        json(status())
                    }
                    path == "/api/express" && session.method == Method.GET -> json(packages(false))
                    path == "/api/pickup" && session.method == Method.GET -> json(packages(true))
                    path == "/api/sync" && session.method == Method.GET -> json(syncState)
                    path == "/api/sync" && session.method == Method.POST -> {
                        val includeHistory = JSONObject(body(session).ifBlank { "{}" }).optBoolean("includeHistory", false)
                        if (SyncEngine.isRunning || !busy.compareAndSet(false, true)) return error(Response.Status.CONFLICT, "sync running")
                        syncState = JSONObject().put("running", true).put("startedAt", System.currentTimeMillis())
                        scope.launch {
                            try {
                                val report = SyncEngine.sync(App.topActivity ?: ctx, includeHistory = includeHistory)
                                syncState = JSONObject().put("running", false).put("ok", true)
                                    .put("finishedAt", System.currentTimeMillis()).put("total", report.total)
                                    .put("statuses", JSONArray().apply {
                                        report.statuses.forEach { s -> put(JSONObject().put("channel", s.channel).put("count", s.count).put("error", s.error.orEmpty())) }
                                    })
                                notifyUi()
                            } catch (_: Exception) {
                                syncState = JSONObject().put("running", false).put("ok", false)
                                    .put("finishedAt", System.currentTimeMillis()).put("error", "手机同步未完成，请在 App 中检查登录状态")
                            } finally { busy.set(false) }
                        }
                        json(JSONObject().put("ok", true).put("running", true), Response.Status.ACCEPTED)
                    }
                    else -> error(Response.Status.NOT_FOUND, "not found")
                }
            } catch (e: CainiaoServiceException) {
                notifyUi()
                json(JSONObject().put("ok", false).put("errorCode", "CAINIAO_${e.kind.name}")
                    .put("error", e.kind.message).put("identity", status().getJSONObject("identity")),
                    if (e.kind in setOf(CainiaoServiceException.Kind.LOGIN_EXPIRED, CainiaoServiceException.Kind.NO_SESSION,
                            CainiaoServiceException.Kind.AUTO_RENEW_NOT_CONFIGURED))
                        Response.Status.CONFLICT else Response.Status.SERVICE_UNAVAILABLE)
            } catch (_: Exception) {
                // Parser exceptions can embed the entire secret JSON input. Never log them.
                error(Response.Status.BAD_REQUEST, "操作失败；请检查请求、材料和登录状态，已有有效材料仍保留")
            }
        }

        private fun body(s: IHTTPSession): String {
            val length = s.headers["content-length"]?.toIntOrNull() ?: error("Content-Length required")
            require(length in 1..4_000_000)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = s.inputStream.read(bytes, offset, length - offset)
                check(count > 0)
                offset += count
            }
            return String(bytes, Charsets.UTF_8)
        }

        private fun notifyUi() {
            scope.launch {
                when (val activity = App.topActivity) {
                    is com.halo.expressassistant.ui.MainActivity -> activity.reload()
                    is com.halo.expressassistant.ui.DetailActivity -> activity.reloadFromStore()
                }
                com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(ctx)
            }
        }

        private fun status(): JSONObject {
            val result = JSONObject().put("ok", true).put("version", UpdateChecker.versionName(ctx))
                .put("items", Store.items(ctx).size)
                .put("accounts", JSONObject().apply { Store.CHANNELS.forEach { put(it, Store.accounts(ctx, it).size) } })
            val identity = JSONObject().put("available", false)
            runCatching {
                val s = CainiaoSessionStore.read(ctx) ?: return@runCatching
                val m = CainiaoIdentity.parse(s.getJSONObject("material"))
                identity.put("materialExpiresAtMs", m.expiresAtMs).put("periodSeconds", m.periodSeconds)
                    .put("lastRefreshedAtMs", s.optLong("lastRefreshedAtMs"))
                val snapshot = CainiaoRenewal.snapshot(ctx)
                identity.put("lastRefreshAttemptAtMs", snapshot.lastAttemptAtMs)
                    .put("autoRenewConfigured", s.optJSONObject("member") != null && s.optJSONObject("native") != null)
                    .put("canAutoRenew", snapshot.canAutoRenew)
                    .put("sessionExpiresAtMs", s.optJSONObject("member")?.let { CainiaoMember.sessionExpiresAt(it) } ?: 0)
                    .put("refreshTokenExpiresAtMs", s.optJSONObject("member")?.let { CainiaoMember.refreshExpiresAt(it) } ?: 0)
                    .put("lastSessionRenewedAtMs", s.optLong("lastSessionRenewedAtMs"))
                    .put("lastSessionRenewAttemptAtMs", s.optLong("lastSessionRenewAttemptAtMs"))
                    .put("refreshErrorCode", snapshot.error?.let { "CAINIAO_${it.name}" }.orEmpty())
                    .put("refreshError", snapshot.error?.message.orEmpty())
                    .put("needsLogin", s.optBoolean("memberNeedsLogin") || snapshot.error in setOf(CainiaoServiceException.Kind.LOGIN_EXPIRED, CainiaoServiceException.Kind.NO_SESSION) && !snapshot.canAutoRenew)
                val shown = CainiaoIdentity.display(m, snapshot.online)
                if (shown != null) {
                    identity.put("available", true).put("codeLength", shown.value.length)
                        .put("source", if (shown.online) "online" else "offline").put("nextChangeAtMs", shown.expiresAtMs)
                }
            }
            return result.put("identity", identity)
        }

        private fun packages(pickupOnly: Boolean): JSONArray {
            val goods = Store.jdGoods(ctx)
            return JSONArray().apply {
                Store.items(ctx).forEach { item ->
                    val done = item.isCompleted() || item.state == 4 || item.stateNum in 108..111
                    val code = if (done) "" else item.pickupCode
                    if (!pickupOnly || !done && (code.isNotBlank() || listOf("待取件", "驿站", "快递柜").any { item.latestText.contains(it) })) {
                        put(JSONObject(Store.json.encodeToString(item)).put("pickupCode", code)
                            .put("orderOnly", item.orderOnly).put("orderId", if (item.source in setOf("taobao", "pdd", "jd")) item.queryChannel else "")
                            .put("goodsName", goods[item.mailNo]?.name.orEmpty()))
                    }
                }
            }
        }
    }
}
