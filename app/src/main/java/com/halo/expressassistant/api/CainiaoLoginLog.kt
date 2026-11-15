package com.halo.expressassistant.api

import org.json.JSONObject
import java.net.URLDecoder

/** Keeps only the current official app's minimal credentials in memory. */
internal class CainiaoLoginLog {
    data class Candidate(val member: JSONObject, val device: JSONObject, val cookie: String, val hasCapturedMember: Boolean) {
        fun sameCredentials(other: Candidate): Boolean = member.toString() == other.member.toString() &&
            device.toString() == other.device.toString() && cookie == other.cookie
    }

    private var capturedMember: JSONObject? = null
    private var cookie = ""
    private var cookieAccount = ""
    private var requestDevice: JSONObject? = null

    fun accept(line: String, existing: JSONObject?, now: Long = System.currentTimeMillis()): Candidate? {
        parseMember(line, now)?.let { capturedMember = it }
        if (line.contains("headers", true) && line.contains("cnMobileSid=")) {
            val headers = Regex("""(?:\{|,\s+)([A-Za-z0-9_-]+)=(.*?)(?=,\s+[A-Za-z0-9_-]+=|\}\s*$)""")
                .findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
            val account = decode(headers["x-uid"].orEmpty())
            val value = headers["cookie"].orEmpty()
            if (account.matches(Regex("[0-9]{1,19}")) && value.contains("cnMobileSid=") &&
                value.none { it == '\r' || it == '\n' }) {
                cookie = value
                cookieAccount = account
                val utdid = decode(headers["x-utdid"].orEmpty())
                val ttid = decode(headers["x-ttid"].orEmpty())
                requestDevice = JSONObject().put("utdid", utdid).put("ttid", ttid)
                    .takeIf { runCatching { CainiaoMember.validateNative(it) }.isSuccess }
            }
        }
        val member = capturedMember ?: existing?.optJSONObject("member") ?: return null
        if (!CainiaoMember.canRenew(member, now)) return null
        val account = member.optString("cnAccountId")
        // Cookie-only recovery may reuse an encrypted member only for this same account.
        if (cookie.isBlank() || cookieAccount != account) return null
        val usingSavedMember = capturedMember == null
        val native = if (usingSavedMember) existing?.optJSONObject("native") ?: return null
            else requestDevice ?: return null
        return Candidate(JSONObject(member.toString()), JSONObject(native.toString()), cookie, !usingSavedMember)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault("")

    companion object {
        fun parseMember(line: String, now: Long = System.currentTimeMillis()): JSONObject? {
            val start = line.indexOf("SessionInfo(")
            if (start < 0) return null
            val text = line.substring(start)
            val member = JSONObject()
            for (key in listOf("sessionId", "refreshToken", "cnAccountId", "tenantId", "sessionIdExpired", "refreshTokenExpired")) {
                val value = Regex("""(?:\(|\{|,\s*)$key=([^,}\)\s]{1,512})""")
                    .find(text)?.groupValues?.get(1) ?: return null
                if (value == "null") return null
                member.put(key, value)
            }
            return member.takeIf { CainiaoMember.canRenew(it, now) }
        }
    }
}
