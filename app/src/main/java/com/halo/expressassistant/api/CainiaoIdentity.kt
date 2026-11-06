package com.halo.expressassistant.api

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Cainiao 8.11.1023: the HMAC input is hex TEXT, rather than standard TOTP bytes. */
object CainiaoIdentity {
    data class OnlineCode(val value: String, val expiresAtMs: Long)
    data class DisplayCode(val value: String, val expiresAtMs: Long, val online: Boolean)

    fun parseOnline(model: JSONObject, fetchedAtMs: Long = System.currentTimeMillis()): OnlineCode {
        val code = model.optString("identityCode")
        val seconds = model.optLong("expireTime", 0)
        require(model.optString("certificated").equals("true", true) &&
            code.matches(Regex("CS8[0-9]{11,19}[A-F]")) && seconds in 1..86400) { "在线身份码资料无效" }
        return OnlineCode(code, fetchedAtMs + seconds * 1000)
    }

    fun display(material: Material?, online: OnlineCode?, nowMs: Long = System.currentTimeMillis()): DisplayCode? {
        if (online != null && nowMs < online.expiresAtMs) return DisplayCode(online.value, online.expiresAtMs, true)
        if (material == null || nowMs >= material.expiresAtMs) return null
        val next = ((nowMs / 1000 / material.periodSeconds) + 1) * material.periodSeconds * 1000
        return DisplayCode(generate(material, nowMs), minOf(next, material.expiresAtMs), false)
    }

    data class Material(
        val key: String,
        val userIndex: Long,
        val digits: Int,
        val periodSeconds: Long,
        val expiresAtMs: Long,
        val codeType: Char
    ) {
        fun validate() {
            require(userIndex in 1 until 10_000_000_000L) { "身份码账号索引无效" }
            require(digits in 1..9 && periodSeconds in 1..86400) { "身份码周期或位数无效" }
            require(codeType in 'A'..'F' && expiresAtMs > 0) { "身份码材料格式无效" }
            require(Base64.getDecoder().decode(key).isNotEmpty()) { "身份码材料缺少密钥" }
        }
    }

    fun parse(o: JSONObject): Material {
        require(o.optString("certificated").equals("true", ignoreCase = true)) { "未取得已认证的身份码材料" }
        return Material(
            o.getString("finalKeyStr"), o.getLong("userIndex"), o.getInt("totpTokenDigits"),
            o.getLong("identityOfflineCodeTotpPeriod"), o.getLong("expireTime"),
            o.getString("codeType").single()
        ).also { it.validate() }
    }

    private fun swap(value: Long): Long {
        require(value in 0 until 10_000_000_000L) { "身份码索引超出范围" }
        val c = value.toString().padStart(10, '0').toCharArray()
        for ((a, b) in listOf(1 to 9, 2 to 8, 3 to 7, 4 to 6)) {
            val temp = c[a]; c[a] = c[b]; c[b] = temp
        }
        return String(c).toLong()
    }

    fun generate(m: Material, nowMs: Long = System.currentTimeMillis()): String {
        m.validate()
        require(nowMs >= 0 && nowMs < m.expiresAtMs) { "身份码材料已到期，请联网更新" }
        val counter = nowMs / 1000 / m.periodSeconds
        val input = counter.toString(16).padStart(16, '0').toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(m.key), "HmacSHA1"))
        val hash = mac.doFinal(input)
        val offset = hash.last().toInt() and 15
        var number = 0L
        for (i in offset until offset + 4) number = (number shl 8) or (hash[i].toLong() and 255)
        var modulo = 1L
        repeat(m.digits) { modulo *= 10 }
        val otp = (number and 0x7fffffff) % modulo
        val factor = m.codeType - 'A' + 1
        var mixed = m.userIndex + factor * otp
        mixed = swap(mixed) + otp
        mixed = swap(mixed) + factor * otp
        mixed = swap(mixed) + otp
        mixed = swap(mixed)
        return "CS8${mixed.toString().padStart(10, '0')}${otp.toString().padStart(m.digits, '0')}${m.codeType}"
    }
}
