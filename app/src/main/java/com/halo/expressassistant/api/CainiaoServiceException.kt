package com.halo.expressassistant.api

/** Only fixed, credential-free errors may cross the UI/API boundary. */
class CainiaoServiceException(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val message: String) {
        LOGIN_EXPIRED("菜鸟登录已过期，请重新登录"),
        NO_SESSION("尚未登录菜鸟"),
        NETWORK("连接菜鸟失败，请检查网络后重试"),
        REJECTED("菜鸟暂未允许本次请求，请稍后重试"),
        INVALID_RESPONSE("菜鸟返回的身份码资料无效，请稍后重试")
    }

    companion object {
        fun fromCodes(codes: List<String>): CainiaoServiceException = CainiaoServiceException(
            if (codes.any { it.substringBefore("::") in setOf("FAIL_SYS_SESSION_EXPIRED", "FAIL_SYS_SESSION_EXPIRED_GUOGUO", "FAIL_SYS_ILLEGAL_ACCESS_TOKEN") })
                Kind.LOGIN_EXPIRED else Kind.REJECTED
        )
    }
}
