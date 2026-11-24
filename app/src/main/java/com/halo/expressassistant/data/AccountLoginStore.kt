package com.halo.expressassistant.data

import android.content.Context
import org.json.JSONObject

/** Non-secret UI state. Credentials stay in their existing stores. */
object AccountLoginStore {
    const val CHANGE_KEY = "login_state_changed"
    const val CAINIAO = "cainiao"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("express_store", Context.MODE_PRIVATE)
    private fun key(channel: String, id: String) = "login_state_${channel}_$id"

    fun status(ctx: Context, channel: String, id: String = ""): LoginStatus =
        runCatching { LoginStatus.valueOf(prefs(ctx).getString(key(channel, id), "UNKNOWN")!!) }.getOrDefault(LoginStatus.UNKNOWN)

    private fun write(ctx: Context, channel: String, id: String, state: LoginStatus) {
        if (status(ctx, channel, id) == state) return
        prefs(ctx).edit().putString(key(channel, id), state.name)
            .putLong(CHANGE_KEY, prefs(ctx).getLong(CHANGE_KEY, 0L) + 1L).apply()
    }

    fun record(ctx: Context, channel: String, requested: BoundAccount, authenticated: Boolean, error: String?) = synchronized(Store) {
        if (!LoginStatus.accepts(Store.accountById(ctx, channel, requested.id), requested)) return@synchronized
        write(ctx, channel, requested.id, LoginStatus.after(status(ctx, channel, requested.id), authenticated, error))
    }

    fun signedIn(ctx: Context, channel: String, id: String) = synchronized(Store) {
        val current = Store.accountById(ctx, channel, id) ?: return@synchronized
        Store.updateAccount(ctx, channel, current.copy(loginGeneration = current.loginGeneration + 1L))
        write(ctx, channel, id, LoginStatus.VALID)
    }

    fun cainiao(ctx: Context, session: JSONObject?) = synchronized(Store) {
        val expired = session?.let {
            it.optBoolean("memberNeedsLogin") || it.optString("lastRefreshError") == "LOGIN_EXPIRED" ||
                it.optString("lastSessionRenewError") == "LOGIN_EXPIRED"
        } == true
        val verified = session != null && session.optString("lastRefreshError").isBlank() &&
            session.optString("lastSessionRenewError").isBlank()
        write(ctx, CAINIAO, "", when {
            session == null -> LoginStatus.UNKNOWN
            expired -> LoginStatus.EXPIRED
            verified -> LoginStatus.VALID
            else -> status(ctx, CAINIAO)
        })
    }

    fun expiredChannels(ctx: Context): List<String> = buildList {
        if (status(ctx, CAINIAO) == LoginStatus.EXPIRED) add(CAINIAO)
        for (channel in Store.CHANNELS) if (Store.accounts(ctx, channel).any {
            it.enabled && status(ctx, channel, it.id) == LoginStatus.EXPIRED
        }) add(channel)
    }

    fun name(channel: String): String = when (channel) {
        Store.CH_XIAOMI -> "小米"; Store.CH_JD -> "京东"; Store.CH_TAOBAO -> "淘宝"; Store.CH_PDD -> "拼多多"; else -> "菜鸟"
    }
}
