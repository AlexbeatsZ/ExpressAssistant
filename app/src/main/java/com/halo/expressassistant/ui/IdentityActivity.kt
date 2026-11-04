package com.halo.expressassistant.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.halo.expressassistant.api.CainiaoIdentity
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.data.CainiaoSessionStore
import com.halo.expressassistant.data.PhoneProvisioning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class IdentityActivity : AppCompatActivity() {
    private lateinit var barcode: ImageView
    private lateinit var codeText: TextView
    private lateinit var timer: TextView
    private lateinit var expiry: TextView
    private lateinit var status: TextView
    private lateinit var refresh: MaterialButton
    private var material: CainiaoIdentity.Material? = null
    private var previousCode = ""

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    val raw = contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 4_000_000) { "初始化文件过大" }
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        require(bytes.size <= 4_000_000) { "初始化文件过大" }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("无法读取初始化文件")
                    PhoneProvisioning.import(this@IdentityActivity, raw)
                }
                reloadMaterial()
                status.text = message
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "导入失败，请检查初始化文件；现有账号保持可用" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "菜鸟身份码"
            setNavigationIcon(com.halo.expressassistant.R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        fun label(size: Float) = TextView(this).apply {
            textSize = size; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        label(24f).text = "出示条形码取件"
        barcode = ImageView(this).apply {
            contentDescription = "当前菜鸟身份码条形码"; setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_XY; setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        body.addView(barcode, LinearLayout.LayoutParams(-1, dp(160)))
        codeText = label(18f)
        timer = label(16f)
        expiry = label(13f)
        status = label(14f)
        refresh = MaterialButton(this).apply {
            text = "更新身份码材料"
            setOnClickListener { update(force = true) }
        }
        body.addView(refresh)
        body.addView(MaterialButton(this).apply {
            text = "导入初始化文件"
            setOnClickListener { importer.launch(arrayOf("application/json", "text/plain")) }
        })
        body.addView(MaterialButton(this).apply {
            text = "复制本机管理 API 配置"
            setOnClickListener {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("快递助手本机管理 API", com.halo.expressassistant.PhoneControl.configuration(this@IdentityActivity))
                if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                clipboard.setPrimaryClip(clip)
                status.text = "本机管理配置已复制；电脑可通过 ADB 端口转发访问"
            }
        })
        label(12f).text = "联网更新材料后可离线出示。材料有效期与登录有效期分别计算。\n本地生成码尚待驿站扫码验收。"
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        EdgeToEdge.apply(this, root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { render(); delay(1000) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            reloadMaterial()
            if (material != null) update(force = false)
        }
    }

    private suspend fun reloadMaterial() {
        material = withContext(Dispatchers.IO) {
            runCatching { CainiaoSessionStore.read(this@IdentityActivity)?.getJSONObject("material")?.let(CainiaoIdentity::parse) }.getOrNull()
        }
        render()
    }

    fun reloadFromStore() {
        lifecycleScope.launch { reloadMaterial() }
    }

    private fun update(force: Boolean) {
        if (!refresh.isEnabled) return
        refresh.isEnabled = false
        lifecycleScope.launch {
            try {
                material = CainiaoRenewal.refresh(this@IdentityActivity, force)
                status.text = if (material == null) "请先导入本人账号的初始化文件" else if (force) "材料更新成功，登录态已保存" else "已加载本机材料；接近到期时会自动更新"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = (e.message ?: "材料更新失败，请稍后重试") + "\n有效旧材料仍可使用" }
            finally { refresh.isEnabled = true; render() }
        }
    }

    private fun render() {
        val m = material
        val now = System.currentTimeMillis()
        if (m == null || now >= m.expiresAtMs) {
            barcode.setImageDrawable(null); codeText.text = ""; previousCode = ""
            timer.text = if (m == null) "请导入初始化文件" else "材料已到期，请联网更新"
            expiry.text = ""
            return
        }
        try {
            val code = CainiaoIdentity.generate(m, now)
            if (code != previousCode) {
                val matrix = MultiFormatWriter().encode(code, BarcodeFormat.CODE_128, 1200, 360, mapOf(EncodeHintType.MARGIN to 24))
                val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
                val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
                bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
                barcode.setImageBitmap(bitmap); codeText.text = code; previousCode = code
            }
            val remaining = m.periodSeconds - now / 1000 % m.periodSeconds
            timer.text = "$remaining 秒后换码 · 可离线出示"
            expiry.text = "材料到期：${DateFormat.getDateTimeInstance().format(Date(m.expiresAtMs))}"
        } catch (_: Exception) {
            barcode.setImageDrawable(null); codeText.text = ""; previousCode = ""
            timer.text = "当前材料无法生成身份码，请更新"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
