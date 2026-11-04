package com.halo.expressassistant.ui

import android.content.Context
import android.util.Log
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.JdGoods
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 卡片外显辅助：取件码解析 + 商品短名整理。
 */
object GoodsPresentation {

    private const val TAG = "GoodsPres"
    private const val MAX_NAME_LEN = 12

    private val pickupRe = Regex("""(取件码|取货码|提货码|自提码|驿站码|取件验证码)[:：\s]*([A-Za-z0-9][A-Za-z0-9\-]{1,12})""")

    /** 从文本中解析取件码；无则 null */
    fun pickupCodeFrom(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = pickupRe.find(text) ?: return null
        return m.groupValues[2]
    }

    /** 合并多段文本解析取件码（轨迹全文场景） */
    fun pickupCodeFromParts(parts: List<String?>): String? {
        for (p in parts) {
            val c = pickupCodeFrom(p)
            if (c != null) return c
        }
        return null
    }

    /** 规则兜底：去掉括号内容、压缩空格、截断 */
    fun ruleShorten(name: String): String {
        var s = name
            .replace(Regex("""【[^】]*】"""), " ")
            .replace(Regex("""（[^）]*）"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace(Regex("""^(仅拆封|官方旗舰店|旗舰店|自营店)"""), "")
            .trim()
        if (s.length > MAX_NAME_LEN) {
            s = s.take(MAX_NAME_LEN)
        }
        return s
    }

    suspend fun shortNameOf(context: Context, mailNo: String, rawName: String): String = withContext(Dispatchers.IO) {
        val cached = Store.jdGoods(context)[mailNo]
        if (cached != null && cached.shortName.isNotBlank()) return@withContext cached.shortName
        val short = ruleShorten(rawName)
        val map = Store.jdGoods(context).toMutableMap()
        map[mailNo] = (map[mailNo] ?: JdGoods(name = rawName)).copy(shortName = short)
        Store.saveJdGoods(context, map)
        short
    }

    suspend fun optimize(context: Context, name: String): String = ruleShorten(name)
}
