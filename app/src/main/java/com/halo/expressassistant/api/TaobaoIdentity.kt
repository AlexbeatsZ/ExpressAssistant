package com.halo.expressassistant.api

import org.json.JSONObject

/** The official Taobao last-mile identity page uses this API, independently of GUOGUO. */
object TaobaoIdentity {
    const val PAGE_URL = "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code"
    // The official page polls every 180 seconds. This is a conservative display window,
    // not a claim that the server guarantees this lifetime or supports offline generation.
    const val REFRESH_INTERVAL_MS = 180_000L

    class Code(val value: String, val fetchedAtMs: Long) {
        val refreshAtMs: Long get() = fetchedAtMs + REFRESH_INTERVAL_MS
        fun usable(nowMs: Long) = nowMs >= fetchedAtMs && nowMs < refreshAtMs
    }
    class Result(val code: Code, val cookie: String)
    enum class Error(val message: String) {
        LOGIN_REQUIRED("淘宝登录已失效，请在设置中重新登录淘宝"),
        NETWORK("连接淘宝失败，请检查网络后重试"),
        UNAVAILABLE("淘宝暂未下发身份码，请稍后重试"),
        INVALID_RESPONSE("淘宝身份码资料无效，请稍后重试")
    }
    class Failure(val error: Error) : Exception(error.message)

    fun fetch(cookie: String): Result {
        if (cookie.isBlank()) throw Failure(Error.LOGIN_REQUIRED)
        try {
            val http = H5Mtop(cookie, PAGE_URL, mapOf(
                "miniappSrc" to "ST", "asac" to "2A24C032ASAUX2BUE25HHF",
                "sourceUrl" to PAGE_URL, "productType" to "YZ"
            ))
            val response = http.call("mtop.cainiao.pickup.identity.getIdentityMessage", JSONObject().put("stationType", "XN"))
            return Result(parse(response), http.cookieHeader)
        } catch (e: CainiaoServiceException) {
            throw Failure(when (e.kind) {
                CainiaoServiceException.Kind.NO_SESSION, CainiaoServiceException.Kind.LOGIN_EXPIRED -> Error.LOGIN_REQUIRED
                CainiaoServiceException.Kind.NETWORK -> Error.NETWORK
                CainiaoServiceException.Kind.INVALID_RESPONSE -> Error.INVALID_RESPONSE
                else -> Error.UNAVAILABLE
            })
        }
    }

    fun parse(data: JSONObject, fetchedAtMs: Long = System.currentTimeMillis()): Code {
        val model = data.optJSONObject("result") ?: data.optJSONObject("data") ?: data
        val value = model.opt("identityCode") as? String ?: throw Failure(Error.UNAVAILABLE)
        if (value.isEmpty()) throw Failure(Error.UNAVAILABLE)
        // Preserve the exact issued value, including leading zeroes; no prefix conversion.
        if (!value.matches(Regex("[!-~]{6,80}"))) throw Failure(Error.INVALID_RESPONSE)
        return Code(value, fetchedAtMs)
    }
}
