package com.halo.expressassistant.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.halo.expressassistant.api.CainiaoIdentity
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.data.CainiaoSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared bottom-tab surface. Protocol and cache policy belong to code and API documentation. */
class IdentityPanel(private val activity: AppCompatActivity) {
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(32))
    }
    val view = ScrollView(activity).apply { isFillViewport = true; addView(body) }
    private val barcode = ImageView(activity).apply {
        contentDescription = "身份码条形码"; setBackgroundColor(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_XY; setPadding(dp(12), dp(16), dp(12), dp(16))
    }
    private val codeText = TextView(activity).apply {
        textSize = 18f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        setPadding(0, dp(20), 0, dp(24))
    }
    private val status = TextView(activity).apply {
        textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(20))
    }
    private val refresh = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "刷新"; setIconResource(com.halo.expressassistant.R.drawable.ic_sync)
        setOnClickListener { update(true) }
    }
    private var material: CainiaoIdentity.Material? = null
    private var previousCode = ""
    private var ticker: Job? = null
    private var visible = false

    init {
        body.addView(barcode, LinearLayout.LayoutParams(-1, dp(160)))
        body.addView(codeText, LinearLayout.LayoutParams(-1, -2))
        body.addView(status, LinearLayout.LayoutParams(-1, -2))
        body.addView(refresh)
    }

    fun show() {
        visible = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        reloadFromStore(); update(false)
        ticker?.cancel()
        ticker = activity.lifecycleScope.launch { while (visible) { render(); delay(1000) } }
    }

    fun hide() {
        visible = false; ticker?.cancel(); ticker = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun reloadFromStore() {
        activity.lifecycleScope.launch {
            material = withContext(Dispatchers.IO) {
                runCatching { CainiaoSessionStore.read(activity)?.getJSONObject("material")?.let(CainiaoIdentity::parse) }.getOrNull()
            }
            render()
        }
    }

    private fun update(force: Boolean) {
        if (!refresh.isEnabled) return
        refresh.isEnabled = false
        if (force) refresh.text = "刷新中"
        activity.lifecycleScope.launch {
            try { material = CainiaoRenewal.refresh(activity, force) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (force) android.widget.Toast.makeText(activity, "刷新失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
            } finally { refresh.isEnabled = true; refresh.text = "刷新"; render() }
        }
    }

    private fun render() {
        val m = material
        val now = System.currentTimeMillis()
        val code = if (m != null) runCatching { CainiaoIdentity.generate(m, now) }.getOrNull() else null
        barcode.visibility = if (code == null) View.GONE else View.VISIBLE
        codeText.visibility = barcode.visibility
        status.visibility = if (code == null) View.VISIBLE else View.GONE
        status.text = if (code == null) "身份码暂不可用" else ""
        if (code == null) { barcode.setImageDrawable(null); codeText.text = ""; previousCode = ""; return }
        if (code == previousCode) return
        val matrix = MultiFormatWriter().encode(code, BarcodeFormat.CODE_128, 1200, 360, mapOf(EncodeHintType.MARGIN to 24))
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
        bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        barcode.setImageBitmap(bitmap); codeText.text = code; previousCode = code
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
