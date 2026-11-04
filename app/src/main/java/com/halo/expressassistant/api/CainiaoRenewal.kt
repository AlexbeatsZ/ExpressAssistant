package com.halo.expressassistant.api

import android.content.Context
import com.halo.expressassistant.data.CainiaoSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CainiaoRenewal {
    private val mutex = Mutex()
    private const val DAY_MS = 86_400_000L

    suspend fun refresh(ctx: Context, force: Boolean = false): CainiaoIdentity.Material? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = CainiaoSessionStore.read(ctx) ?: return@withLock null
            val old = CainiaoIdentity.parse(session.getJSONObject("material"))
            if (!force && old.expiresAtMs - System.currentTimeMillis() > DAY_MS) return@withLock old
            val http = H5Mtop(session.getString("cookie"))
            val data = http.call("mtop.cainiao.nbpickup.native.identitycode.get.cn", JSONObject()
                .put("channel", "GUOGUO").put("codeType", old.codeType.toString()).put("verifyVersion", "V2"))
            val model = data.optJSONObject("model") ?: error("菜鸟未下发身份码材料")
            model.put("codeType", old.codeType.toString())
            val fresh = CainiaoIdentity.parse(model)
            check(fresh.userIndex == old.userIndex) { "返回账号与原材料不同，已保留原材料" }
            check(fresh.expiresAtMs > System.currentTimeMillis() && fresh.expiresAtMs >= old.expiresAtMs) { "返回的材料期限无效，已保留原材料" }
            session.put("cookie", http.cookieHeader).put("material", model).put("lastRefreshedAtMs", System.currentTimeMillis())
            CainiaoSessionStore.save(ctx, session)
            fresh
        }
    }
}
