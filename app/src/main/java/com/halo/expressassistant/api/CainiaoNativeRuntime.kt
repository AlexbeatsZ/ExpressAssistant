package com.halo.expressassistant.api

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Uses the user's installed, signed official runtime; its code is never redistributed. */
internal object CainiaoNativeRuntime {
    private const val PACKAGE = "com.cainiao.wireless"
    private const val CERTIFICATE = "83f9f14c3432915c85c833915fe5f2db6de31c4ceb3bc92e33d341a527dbacdc"
    private class Engine(val component: Any, val api: Class<*>, val sdkVersion: String)
    @Volatile private var cached: Engine? = null

    private class StorageContext(base: Context, private val owner: Context, private val root: File) : ContextWrapper(base) {
        private fun dir(name: String) = File(root, name).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getFilesDir(): File = dir("files")
        override fun getCacheDir(): File = dir("cache")
        override fun getCodeCacheDir(): File = dir("code-cache")
        override fun getNoBackupFilesDir(): File = dir("no-backup")
        override fun getSharedPreferences(name: String, mode: Int) = owner.getSharedPreferences("cainiao-runtime-$name", mode)
        override fun getContentResolver() = owner.contentResolver
        override fun getDir(name: String, mode: Int): File = dir("app_$name")
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
            dataDir = root.absolutePath
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized private fun engine(ctx: Context): Engine {
        cached?.let { return it }
        try {
            val info = ctx.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
            check(info.signatures?.any { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) } == CERTIFICATE
            } == true)
            val official = ctx.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val context = StorageContext(official, ctx.applicationContext, File(ctx.noBackupFilesDir, "cainiao-native-runtime").apply { mkdirs() })
            val loader = official.classLoader
            val managerClass = loader.loadClass("com.alibaba.wireless.security.open.SecurityGuardManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            val store = managerClass.getMethod("getStaticDataStoreComp").invoke(manager)
            val storeApi = loader.loadClass("com.alibaba.wireless.security.open.staticdatastore.IStaticDataStoreComponent")
            check(storeApi.getMethod("getAppKeyByIndex", Int::class.javaPrimitiveType, String::class.java)
                .invoke(store, 0, "") == "23050477")
            val api = loader.loadClass("com.alibaba.wireless.security.open.middletier.IUnifiedSecurityComponent")
            val component = checkNotNull(managerClass.getMethod("getInterface", Class::class.java).invoke(manager, api))
            api.getMethod("init", HashMap::class.java).invoke(component, hashMapOf<String, Any>("authCode" to ""))
            val sdkVersion = loader.loadClass("com.cainiao.cnloginsdk.customer.sdk.constants.Constants")
                .getField("SDK_VERSION").get(null) as String
            check(sdkVersion.matches(Regex("[0-9.]{1,32}")))
            return Engine(component, api, sdkVersion).also { cached = it }
        } catch (_: Exception) {
            throw CainiaoServiceException(CainiaoServiceException.Kind.AUTH_RUNTIME_UNAVAILABLE)
        } catch (_: LinkageError) {
            throw CainiaoServiceException(CainiaoServiceException.Kind.AUTH_RUNTIME_UNAVAILABLE)
        }
    }

    fun sdkVersion(ctx: Context): String = engine(ctx).sdkVersion

    fun sign(ctx: Context, api: String, data: String, member: JSONObject, device: JSONObject, stamp: String): Map<String, String> {
        val engine = engine(ctx)
        try {
            val digest = MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val fields = listOf(device.getString("utdid"), member.optString("cnAccountId"), "", "23050477", digest,
                stamp, api, "1.0", member.getString("sessionId"), device.getString("ttid"), device.optString("deviceId"),
                "", "", "", "27", "", "", "", "", "", "", "")
            val params = hashMapOf<String, Any>("appkey" to "23050477", "data" to fields.joinToString("&"),
                "useWua" to true, "env" to 0, "authCode" to "", "api" to api,
                "extendParas" to hashMapOf("pageId" to "", "pageName" to ""), "requestId" to "expressassistant-renewal")
            val signed = engine.api.getMethod("getSecurityFactors", HashMap::class.java).invoke(engine.component, params) as Map<*, *>
            return listOf("x-sign", "x-sgext", "x-umt", "x-mini-wua", "wua").associateWith { key ->
                (signed[key] as? String)?.takeIf { it.isNotBlank() } ?: error("续登签名不可用")
            }
        } catch (_: Exception) {
            throw CainiaoServiceException(CainiaoServiceException.Kind.AUTH_RUNTIME_UNAVAILABLE)
        } catch (_: LinkageError) {
            throw CainiaoServiceException(CainiaoServiceException.Kind.AUTH_RUNTIME_UNAVAILABLE)
        }
    }
}
