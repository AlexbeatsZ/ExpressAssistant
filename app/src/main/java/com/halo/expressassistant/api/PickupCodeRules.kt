package com.halo.expressassistant.api

import java.text.Normalizer
import java.util.Locale

object PickupCodeRules {
    private const val TOKEN = "([A-Za-z0-9-]+)"
    private val labelled = Regex("""(?:取件验证码|取件密码|取件码|取货码|提货码|自提码|驿站码|开柜码)[\s:：为是“「【\[]*$TOKEN""")
    private val byCode = Regex("""凭(?:码)?[\s:：为是“「【\[]*$TOKEN(?=[^-A-Za-z0-9][^。！？\n]{0,32}(?:取件|取货|领件|领取)|(?:取件|取货|领件|领取))""")
    private val format = Regex("[A-Z0-9]+(?:-[A-Z0-9]+)*")
    private val dashVariants = Regex("[‐‑‒–—−]")

    internal fun normaliseText(text: String): String =
        dashVariants.replace(Normalizer.normalize(text, Normalizer.Form.NFKC), "-")

    fun key(code: String): String = normaliseText(code).trim().uppercase(Locale.ROOT)

    fun fromTracking(text: String): String? = labelled.findAll(normaliseText(text))
        .map { it.groupValues[1] }.firstOrNull(::isValid)

    internal fun smsMatches(normalisedText: String): Sequence<MatchGroup> =
        (labelled.findAll(normalisedText) + byCode.findAll(normalisedText)).mapNotNull { it.groups[1] }

    internal fun isValidSmsCode(code: String): Boolean = isValid(code) && key(code).any { it in '0'..'9' }

    private fun isValid(code: String): Boolean = code.length in 2..13 && format.matches(key(code))
}
