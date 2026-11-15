package com.halo.expressassistant.api

import java.util.Locale

/** 菜鸟首页格式校验，提取时保留完整字符段，防止截断长订单号。 */
object WaybillRules {
    private val characters = Regex("[A-Za-z0-9-]+")
    private val orderNumber = Regex("[0-9]{6}-[0-9]{10,}")
    private val links = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val deliveryContext = Regex(
        "快递|物流|运单|包裹|驿站|速递|速运|派送|派件|取件|取货|顺丰|中通|申通|圆通|韵达|极兔|京东|邮政|德邦|百世|EMS",
        RegexOption.IGNORE_CASE
    )
    private val otherReferences = Regex(
        "(?:订单(?:号|编号|号码)|交易(?:号|编号)|取件(?:码|密码|验证码)|取货码|提货码|自提码|验证码|校验码|手机号|(?:联系|客服)?电话)[\\s:：为是]*([A-Za-z0-9-]+)"
    )

    fun normalise(input: String): String = input.trim().uppercase(Locale.ROOT)

    fun isValid(input: String): Boolean {
        val value = normalise(input)
        return value.length in 12..17 && characters.matches(value) && !orderNumber.matches(value) &&
            value.count { it in '0'..'9' } >= 6 && value.count { it !in '0'..'9' } <= 6
    }

    fun extract(text: String, fromSms: Boolean = false): List<String> {
        if (fromSms && !deliveryContext.containsMatchIn(text)) return emptyList()
        val content = links.replace(text) { " ".repeat(it.value.length) }
        val excluded = otherReferences.findAll(content).map { it.groups[1]!!.range }.toList()
        return characters.findAll(content).filter { match ->
            isValid(match.value) && excluded.none { match.range.first <= it.last && match.range.last >= it.first }
        }.map { normalise(it.value) }.distinct().toList()
    }
}
