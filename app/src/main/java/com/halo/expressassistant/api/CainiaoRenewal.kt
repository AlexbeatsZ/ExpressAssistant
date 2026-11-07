package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.CainiaoSessionStore
import com.halo.expressassistant.data.PhoneProvisioning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CainiaoRenewal {
    private val mutex = Mutex()
    private const val DAY_MS = 86_400_000L

    data class Snapshot(val material: CainiaoIdentity.Material?, val online: CainiaoIdentity.OnlineCode?,
        val lastAttemptAtMs: Long, val error: CainiaoServiceException.Kind?, val canAutoRenew: Boolean = false)

    fun snapshot(ctx: Context): Snapshot {
        val session = CainiaoSessionStore.read(ctx) ?: return Snapshot(null, null, 0, CainiaoServiceException.Kind.NO_SESSION)
        val material = CainiaoIdentity.parse(session.getJSONObject("material"))
        val online = session.optJSONObject("online")?.let {
            val value = it.optString("code")
            if (value.matches(Regex("CS8[0-9]{11,19}[A-F]"))) CainiaoIdentity.OnlineCode(value, it.optLong("expiresAtMs")) else null
        }
        val error = runCatching { CainiaoServiceException.Kind.valueOf(session.optString("lastRefreshError")) }.getOrNull()
        return Snapshot(material, online, session.optLong("lastRefreshAttemptAtMs"), error,
            session.optJSONObject("native") != null && !session.optBoolean("memberNeedsLogin") && CainiaoMember.canRenew(session.optJSONObject("member")))
    }

    private fun recordFailure(ctx: Context, session: JSONObject, error: CainiaoServiceException): Nothing {
        session.put("lastRefreshAttemptAtMs", System.currentTimeMillis()).put("lastRefreshError", error.kind.name)
        CainiaoSessionStore.save(ctx, session)
        throw error
    }

    private fun recordSuccess(ctx: Context, session: JSONObject, http: H5Mtop) {
        session.put("cookie", http.cookieHeader).put("lastRefreshAttemptAtMs", System.currentTimeMillis())
            .put("lastRefreshError", "")
        CainiaoSessionStore.save(ctx, session)
    }

    /** Called only while holding mutex, so token replacement and read retries cannot race. */
    private fun renewMember(ctx: Context, session: JSONObject, force: Boolean = false): Boolean {
        val member = session.optJSONObject("member") ?: return false
        val device = session.optJSONObject("native") ?: return false
        if (session.optBoolean("memberNeedsLogin") || !CainiaoMember.canRenew(member))
            throw CainiaoServiceException(CainiaoServiceException.Kind.LOGIN_EXPIRED)
        if (!force && !CainiaoMember.needsRenewal(member)) return false
        session.put("lastSessionRenewAttemptAtMs", System.currentTimeMillis())
        CainiaoSessionStore.save(ctx, session)
        try {
            val fresh = CainiaoMemberRenewal.renew(ctx, member, device)
            // Save rotated credentials before parsing cookies or requesting another API.
            session.put("member", fresh).put("lastSessionRenewedAtMs", System.currentTimeMillis())
                .put("lastSessionRenewError", "").put("memberNeedsLogin", false)
            CainiaoSessionStore.save(ctx, session)
            session.put("cookie", CainiaoMember.mergeCookies(session.getString("cookie"), fresh))
                .put("lastRefreshError", "")
            CainiaoSessionStore.save(ctx, session)
            return true
        } catch (e: CainiaoServiceException) {
            session.put("lastSessionRenewError", e.kind.name)
            if (e.kind == CainiaoServiceException.Kind.LOGIN_EXPIRED) session.put("memberNeedsLogin", true)
            CainiaoSessionStore.save(ctx, session)
            throw e
        }
    }

    private fun authenticated(ctx: Context, session: JSONObject, api: String, data: JSONObject, alreadyRenewed: Boolean = false): Pair<JSONObject, H5Mtop> {
        val renewed = alreadyRenewed || renewMember(ctx, session)
        var http = H5Mtop(session.getString("cookie"))
        try { return http.call(api, data) to http }
        catch (e: CainiaoServiceException) {
            if (e.kind != CainiaoServiceException.Kind.LOGIN_EXPIRED || renewed || !renewMember(ctx, session, force = true)) throw e
            http = H5Mtop(session.getString("cookie"))
            return http.call(api, data) to http
        }
    }

    suspend fun importConfiguration(ctx: Context, raw: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { PhoneProvisioning.import(ctx, raw) }
    }

    suspend fun renewSession(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = CainiaoSessionStore.read(ctx) ?: throw CainiaoServiceException(CainiaoServiceException.Kind.NO_SESSION)
            try {
                if (!renewMember(ctx, session, force = true))
                    throw CainiaoServiceException(CainiaoServiceException.Kind.AUTO_RENEW_NOT_CONFIGURED)
                true
            } catch (e: CainiaoServiceException) { recordFailure(ctx, session, e) }
            catch (_: Exception) { recordFailure(ctx, session, CainiaoServiceException(CainiaoServiceException.Kind.INVALID_RESPONSE)) }
        }
    }

    suspend fun online(ctx: Context, force: Boolean = false): CainiaoIdentity.OnlineCode = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = CainiaoSessionStore.read(ctx) ?: throw CainiaoServiceException(CainiaoServiceException.Kind.NO_SESSION)
            val cached = snapshot(ctx).online
            if (!force && cached != null && cached.expiresAtMs > System.currentTimeMillis() + 3000) return@withLock cached
            try {
                val (data, http) = authenticated(ctx, session, "mtop.cainiao.nbpickup.identitycode.get.cn", JSONObject()
                    .put("appName", "GUOGUO").put("verifyVersion", "V2"))
                val fresh = CainiaoIdentity.parseOnline(data.getJSONObject("model"))
                session.put("online", JSONObject().put("code", fresh.value).put("expiresAtMs", fresh.expiresAtMs))
                    .put("lastOnlineRefreshedAtMs", System.currentTimeMillis())
                recordSuccess(ctx, session, http)
                fresh
            } catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) { recordFailure(ctx, session, e) }
            catch (_: Exception) { recordFailure(ctx, session, CainiaoServiceException(CainiaoServiceException.Kind.INVALID_RESPONSE)) }
        }
    }

    suspend fun refresh(ctx: Context, force: Boolean = false): CainiaoIdentity.Material? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = CainiaoSessionStore.read(ctx) ?: return@withLock null
            try {
                // Member lifetime is independent of the 15-day material cache.
                val renewed = renewMember(ctx, session)
                val old = CainiaoIdentity.parse(session.getJSONObject("material"))
                if (!force && old.expiresAtMs - System.currentTimeMillis() > DAY_MS) return@withLock old
                val (data, http) = authenticated(ctx, session, "mtop.cainiao.nbpickup.native.identitycode.get.cn", JSONObject()
                    .put("channel", "GUOGUO").put("codeType", old.codeType.toString()).put("verifyVersion", "V2"), alreadyRenewed = renewed)
                val model = data.getJSONObject("model").put("codeType", old.codeType.toString())
                val fresh = CainiaoIdentity.parse(model)
                check(fresh.userIndex == old.userIndex && fresh.expiresAtMs > System.currentTimeMillis() && fresh.expiresAtMs >= old.expiresAtMs)
                session.put("material", model).put("lastRefreshedAtMs", System.currentTimeMillis())
                recordSuccess(ctx, session, http)
                fresh
            } catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) { recordFailure(ctx, session, e) }
            catch (_: Exception) { recordFailure(ctx, session, CainiaoServiceException(CainiaoServiceException.Kind.INVALID_RESPONSE)) }
        }
    }
}
