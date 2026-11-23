package com.halo.expressassistant.data

/** Only explicit authentication failures change login state; transport failures do not. */
enum class LoginStatus {
    UNKNOWN, VALID, EXPIRED;

    companion object {
        fun after(current: LoginStatus, authenticated: Boolean, error: String?): LoginStatus = when {
            isExpiredError(error) -> EXPIRED
            error == null && authenticated -> VALID
            else -> current
        }

        fun isExpiredError(error: String?): Boolean = error != null && listOf(
            "登录已失效", "登录态已失效", "登录失效", "登录已过期", "登录态过期",
            "需要重新登录", "需要在手机重新登录", "SESSION_EXPIRED", "登录过期", "用户未登录",
            "token已过期", "token已失效", "token expired", "session expired"
        ).any { error.contains(it, ignoreCase = true) }

        /** Guard results from an old request after re-login, account removal, or disabling. */
        fun accepts(current: BoundAccount?, requested: BoundAccount): Boolean =
            current?.enabled == true && current.id == requested.id && current.payload == requested.payload &&
                current.loginGeneration == requested.loginGeneration
    }
}
