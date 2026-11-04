package com.halo.expressassistant.data

import android.content.Context
import com.halo.expressassistant.api.CainiaoIdentity
import kotlinx.serialization.decodeFromString
import org.json.JSONObject

/** Authenticated maintenance import. Validate the whole payload before changing app data. */
object PhoneProvisioning {
    fun import(ctx: Context, raw: String): String {
        require(raw.length <= 4_000_000) { "初始化文件过大" }
        val root = JSONObject(raw)
        require(root.getInt("schema") == 1) { "不支持的初始化文件版本" }
        val session = root.optJSONObject("cainiao")
        if (session != null) {
            require(session.getString("cookie").isNotBlank()) { "缺少菜鸟登录态" }
            val material = CainiaoIdentity.parse(session.getJSONObject("material"))
            require(material.expiresAtMs > System.currentTimeMillis()) { "导入材料已到期" }
        }
        val accounts = root.optJSONObject("accounts")
        val decoded = Store.CHANNELS.associateWith { ch ->
            Store.json.decodeFromString<List<BoundAccount>>(accounts?.optJSONArray(ch)?.toString() ?: "[]")
        }
        val items = Store.json.decodeFromString<List<ExpressItem>>(root.optJSONArray("items")?.toString() ?: "[]")
            .map { if (it.isCompleted()) it.copy(state = 3, stateNum = 107, pickupCode = "") else it }
        val goods = Store.json.decodeFromString<Map<String, JdGoods>>(root.optJSONObject("goods")?.toString() ?: "{}")
        require(items.all { it.mailNo.isNotBlank() }) { "初始化快递缺少单号" }
        require(session != null || items.isNotEmpty() || decoded.values.any { it.isNotEmpty() } || goods.isNotEmpty()) { "导入内容为空" }
        // Parcel/account-only imports preserve the existing encrypted identity session.
        if (session != null) CainiaoSessionStore.save(ctx, session)
        decoded.forEach { (ch, rows) -> rows.forEach { Store.addAccount(ctx, ch, it) } }
        Store.saveItems(ctx, (items + Store.items(ctx)).distinctBy { it.mailNo })
        Store.saveJdGoods(ctx, goods + Store.jdGoods(ctx))
        return "导入完成：${decoded.values.sumOf { it.size }} 个平台账号、${items.size} 件快递"
    }
}
