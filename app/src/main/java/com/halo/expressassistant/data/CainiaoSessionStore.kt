package com.halo.expressassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.halo.expressassistant.api.CainiaoIdentity
import com.halo.expressassistant.api.CainiaoMember
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-install encrypted storage, excluded from Android backup. */
object CainiaoSessionStore {
    private const val ALIAS = "expressassistant.cainiao.session.v1"
    private fun file(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, "cainiao-session.enc"))
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun read(ctx: Context): JSONObject? {
        if (!file(ctx).baseFile.exists()) return null
        val bytes = file(ctx).readFully()
        require(bytes.size > 29 && bytes[0].toInt() == 1) { "身份码存储格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }

    @Synchronized fun save(ctx: Context, session: JSONObject) {
        require(session.getString("cookie").isNotBlank()) { "缺少菜鸟登录态" }
        CainiaoIdentity.parse(session.getJSONObject("material"))
        session.optJSONObject("member")?.let { CainiaoMember.validate(it) }
        session.optJSONObject("native")?.let { CainiaoMember.validateNative(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = byteArrayOf(1) + cipher.iv + cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        val target = file(ctx)
        val stream = target.startWrite()
        try { stream.write(data); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
}
