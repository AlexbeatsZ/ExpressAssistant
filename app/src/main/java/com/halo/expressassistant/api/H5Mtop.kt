package com.halo.expressassistant.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A private, manually managed cookie header avoids duplicate H5 tokens. No request logging. */
internal class H5Mtop(header: String) {
    private val cookies = linkedMapOf<String, String>()
    val cookieHeader: String get() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    init {
        header.split(';').forEach {
            val p = it.trim().split('=', limit = 2)
            if (p.size == 2 && p[0].isNotBlank()) cookies[p[0]] = p[1]
        }
        require(cookies.isNotEmpty()) { "缺少登录会话，请重新导入" }
    }

    private fun request(url: okhttp3.HttpUrl): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Cookie", cookieHeader)
            .header("Origin", "https://h5.m.taobao.com")
            .header("Referer", "https://h5.m.taobao.com/")
            .header("Accept", "application/json").build()
        try {
            return client.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "菜鸟请求失败（HTTP ${r.code}）" }
                for (line in r.headers.values("Set-Cookie")) {
                    val first = line.substringBefore(';').split('=', limit = 2)
                    if (first.size == 2) {
                        if (first[1].isEmpty() || Regex("(?i)(^|;)\\s*max-age=0(;|$)").containsMatchIn(line)) cookies.remove(first[0])
                        else cookies[first[0]] = first[1]
                    }
                }
                val raw = r.body?.string().orEmpty()
                try { JSONObject(raw) } catch (_: Exception) { error("菜鸟返回了无法解析的响应") }
            }
        } catch (_: IOException) {
            error("连接菜鸟失败，请检查网络后重试")
        }
    }

    fun call(api: String, data: JSONObject): JSONObject {
        val base = "https://h5api.m.taobao.com/h5/$api/1.0/".toHttpUrl()
        // The unsigned request may return TOKEN_EMPTY while issuing fresh tokens.
        request(base.newBuilder().addQueryParameter("appKey", APPKEY).build())
        val token = cookies["_m_h5_tk"]?.substringBefore('_')?.takeIf { it.isNotBlank() }
            ?: error("菜鸟未下发会话令牌，请重新导入登录态")
        val stamp = System.currentTimeMillis().toString()
        val raw = data.toString()
        val sign = MessageDigest.getInstance("MD5").digest("$token&$stamp&$APPKEY&$raw".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val result = request(base.newBuilder().addQueryParameter("appKey", APPKEY)
            .addQueryParameter("t", stamp).addQueryParameter("sign", sign)
            .addQueryParameter("type", "originaljson").addQueryParameter("dataType", "json")
            .addQueryParameter("data", raw).build())
        val ret = result.optJSONArray("ret")
        val codes = (0 until (ret?.length() ?: 0)).map {
            ret!!.optString(it).substringBefore("::").filter { c -> c in 'A'..'Z' || c.isDigit() || c == '_' }.take(80)
        }
        check("SUCCESS" in codes) { "菜鸟拒绝请求：${codes.joinToString().ifBlank { "UNKNOWN" }}；必要时重新导入登录态" }
        return result.optJSONObject("data") ?: JSONObject()
    }

    companion object {
        private const val APPKEY = "12574478"
        private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }
}
