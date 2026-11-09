package com.halo.expressassistant.api

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal object CainiaoMemberRenewal {
    private const val API = "mtop.cainiao.cnmember.customer.autologin"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun renew(ctx: Context, previous: JSONObject, device: JSONObject): JSONObject {
        if (!CainiaoMember.canRenew(previous)) throw CainiaoServiceException(CainiaoServiceException.Kind.LOGIN_EXPIRED)
        val data = CainiaoMember.request(previous, device, CainiaoNativeRuntime.sdkVersion(ctx))
        return CainiaoMember.accept(call(ctx, API, data, previous, device), previous)
    }

    fun call(ctx: Context, api: String, payload: JSONObject, previous: JSONObject, device: JSONObject): JSONObject {
        try {
            val data = payload.toString()
            val stamp = (System.currentTimeMillis() / 1000).toString()
            val signed = CainiaoNativeRuntime.sign(ctx, api, data, previous, device, stamp)
            fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            val url = "https://cn-acs.m.cainiao.com/gw/$api/1.0/".toHttpUrl().newBuilder()
                .addQueryParameter("data", data).addQueryParameter("wua", signed.getValue("wua")).build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android ${android.os.Build.VERSION.RELEASE})")
                .header("x-biz-type", "cainiao_c_user").header("x-biz-info", "env=cainiao_c_user")
                .header("cn_account_lang", "zh_CN").header("x-cn-instance", "true")
                .header("x-appkey", "23050477").header("x-pv", "6.3").header("x-features", "27").header("x-t", stamp)
                .header("x-ttid", encode(device.getString("ttid"))).header("x-utdid", encode(device.getString("utdid")))
                .header("x-sid", encode(previous.getString("sessionId"))).header("x-uid", previous.optString("cnAccountId"))
                .header("x-cn-member-router-id", previous.optString("cnAccountId"))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .apply { listOf("x-sign", "x-sgext", "x-umt", "x-mini-wua").forEach { header(it, encode(signed.getValue(it))) } }
                .build()
            val response = client.newCall(request).execute().use {
                if (!it.isSuccessful) throw CainiaoServiceException(CainiaoServiceException.Kind.REJECTED)
                JSONObject(it.body?.string().orEmpty())
            }
            val ret = response.optJSONArray("ret")
            val codes = (0 until (ret?.length() ?: 0)).map { ret!!.optString(it).substringBefore("::") }
            if ("SUCCESS" !in codes) throw CainiaoServiceException.fromCodes(codes)
            return response
        } catch (error: CainiaoServiceException) { throw error }
        catch (_: IOException) { throw CainiaoServiceException(CainiaoServiceException.Kind.NETWORK) }
        catch (_: Exception) { throw CainiaoServiceException(CainiaoServiceException.Kind.INVALID_RESPONSE) }
    }
}
