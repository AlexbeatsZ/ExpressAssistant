package com.halo.expressassistant.api

/** A valid H5 session does not need native signing; renew only after an explicit expiry. */
internal object CainiaoSessionAccess {
    fun <T> read(request: () -> T, renew: () -> Boolean): T {
        try { return request() }
        catch (e: CainiaoServiceException) {
            if (e.kind != CainiaoServiceException.Kind.LOGIN_EXPIRED || !renew()) throw e
        }
        return request()
    }
}
