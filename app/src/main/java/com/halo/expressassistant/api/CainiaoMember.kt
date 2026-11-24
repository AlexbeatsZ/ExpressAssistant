package com.halo.expressassistant.api

import org.json.JSONObject
import java.net.HttpCookie

/** Member credentials stay inside the encrypted identity session. */
object CainiaoMember {
    // A 13-hour window ensures a 12-hour worker can renew before a 24-hour session expires.
    const val RENEW_BEFORE_MS = 13 * 60 * 60 * 1000L

    fun validate(member: JSONObject) {
        require(member.optString("sessionId").isNotBlank() && member.optString("refreshToken").isNotBlank())
        require(member.optString("cnAccountId").matches(Regex("[0-9]{1,19}")) && member.optString("cnAccountId").toLongOrNull() != null)
        require(member.optInt("tenantId") == 78)
        require(sessionExpiresAt(member) > 0 && refreshExpiresAt(member) > 0)
    }

    fun sessionExpiresAt(member: JSONObject): Long = member.optJSONObject("ext")?.optLong("sessionIdExpired")
        ?.takeIf { it > 0 } ?: member.optLong("sessionIdExpired")

    fun refreshExpiresAt(member: JSONObject): Long = member.optJSONObject("ext")?.optLong("refreshTokenExpired")
        ?.takeIf { it > 0 } ?: member.optLong("refreshTokenExpired")

    fun canRenew(member: JSONObject?, now: Long = System.currentTimeMillis()): Boolean =
        member != null && runCatching { validate(member); refreshExpiresAt(member) > now }.getOrDefault(false)

    fun needsRenewal(member: JSONObject, now: Long = System.currentTimeMillis()): Boolean =
        sessionExpiresAt(member) <= now + RENEW_BEFORE_MS

    fun validateNative(device: JSONObject) {
        require(device.optString("utdid").isNotBlank() && device.optString("utdid").length <= 256)
        require(device.optString("ttid").isNotBlank() && device.optString("ttid").length <= 256)
    }

    fun request(member: JSONObject, device: JSONObject, sdkVersion: String): JSONObject {
        validate(member); validateNative(device)
        val payload = JSONObject().put("accountId", member.optString("cnAccountId").toLong())
            .put("refreshToken", member.getString("refreshToken")).put("appKey", "23050477")
            .put("tenantId", 78).put("utdid", device.getString("utdid")).put("sdkVersion", sdkVersion)
        // The SDK serializes this field as a JSON string, rather than a nested object.
        return JSONObject().put("autoLoginRequest", payload.toString())
    }

    fun accept(response: JSONObject, previous: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        val fresh = response.getJSONObject("data").getJSONObject("data")
        validate(fresh)
        require(fresh.optString("cnAccountId") == previous.optString("cnAccountId"))
        require(sessionExpiresAt(fresh) > now && refreshExpiresAt(fresh) > now)
        require(fresh.getJSONObject("ext").getJSONArray("loginCookie").length() > 0)
        return JSONObject(fresh.toString()).put("cnAccountId", fresh.optString("cnAccountId"))
    }

    fun mergeCookies(current: String, member: JSONObject): String {
        val cookies = linkedMapOf<String, String>()
        current.split(';').forEach { line ->
            val pair = line.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) cookies[pair[0]] = pair[1]
        }
        val incoming = member.getJSONObject("ext").getJSONArray("loginCookie")
        var updatedSid = false
        for (i in 0 until incoming.length()) {
            for (cookie in HttpCookie.parse(incoming.getString(i))) {
                val domain = cookie.domain.orEmpty().trimStart('.').lowercase()
                require(domain == "cainiao.com" || domain.endsWith(".cainiao.com") ||
                    domain == "taobao.com" || domain.endsWith(".taobao.com"))
                require(cookie.name.matches(Regex("[A-Za-z0-9_.$-]+")))
                require(cookie.value.none { it == '\r' || it == '\n' || it == ';' })
                if (cookie.maxAge == 0L || cookie.value.isEmpty()) cookies.remove(cookie.name)
                else {
                    cookies[cookie.name] = cookie.value
                    if (cookie.name == "cnMobileSid") updatedSid = true
                }
            }
        }
        require(updatedSid) { "续登响应未包含身份会话" }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
