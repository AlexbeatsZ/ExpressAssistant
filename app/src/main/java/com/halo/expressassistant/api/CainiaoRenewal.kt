package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.CainiaoSessionStore
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
        val lastAttemptAtMs: Long, val error: CainiaoServiceException.Kind?)

    fun snapshot(ctx: Context): Snapshot {
        val session = CainiaoSessionStore.read(ctx) ?: return Snapshot(null, null, 0, CainiaoServiceException.Kind.NO_SESSION)
        val material = CainiaoIdentity.parse(session.getJSONObject("material"))
        val online = session.optJSONObject("online")?.let {
            val value = it.optString("code")
            if (value.matches(Regex("CS8[0-9]{11,19}[A-F]"))) CainiaoIdentity.OnlineCode(value, it.optLong("expiresAtMs")) else null
        }
        val error = runCatching { CainiaoServiceException.Kind.valueOf(session.optString("lastRefreshError")) }.getOrNull()
        return Snapshot(material, online, session.optLong("lastRefreshAttemptAtMs"), error)
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

    suspend fun online(ctx: Context, force: Boolean = false): CainiaoIdentity.OnlineCode = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = CainiaoSessionStore.read(ctx) ?: throw CainiaoServiceException(CainiaoServiceException.Kind.NO_SESSION)
            val cached = snapshot(ctx).online
            if (!force && cached != null && cached.expiresAtMs > System.currentTimeMillis() + 3000) return@withLock cached
            try {
                val http = H5Mtop(session.getString("cookie"))
                val data = http.call("mtop.cainiao.nbpickup.identitycode.get.cn", JSONObject()
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
            val old = CainiaoIdentity.parse(session.getJSONObject("material"))
            if (!force && old.expiresAtMs - System.currentTimeMillis() > DAY_MS) return@withLock old
            try {
                val http = H5Mtop(session.getString("cookie"))
                val data = http.call("mtop.cainiao.nbpickup.native.identitycode.get.cn", JSONObject()
                    .put("channel", "GUOGUO").put("codeType", old.codeType.toString()).put("verifyVersion", "V2"))
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
