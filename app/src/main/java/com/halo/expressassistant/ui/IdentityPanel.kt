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
import com.halo.expressassistant.api.CainiaoServiceException
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
    private var state = CainiaoRenewal.Snapshot(null, null, 0, null)
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
        ticker = activity.lifecycleScope.launch {
            while (visible) {
                render()
                if (state.material != null && state.error != CainiaoServiceException.Kind.LOGIN_EXPIRED &&
                    (state.online?.expiresAtMs ?: 0) <= System.currentTimeMillis() &&
                    System.currentTimeMillis() - state.lastAttemptAtMs >= 60_000) update(false)
                delay(1000)
            }
        }
    }

    fun hide() {
        visible = false; ticker?.cancel(); ticker = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun reloadFromStore() {
        activity.lifecycleScope.launch {
            state = withContext(Dispatchers.IO) {
                runCatching { CainiaoRenewal.snapshot(activity) }.getOrDefault(CainiaoRenewal.Snapshot(null, null, 0, CainiaoServiceException.Kind.INVALID_RESPONSE))
            }
            render()
        }
    }

    private fun update(force: Boolean) {
        if (!refresh.isEnabled) return
        if (!force && state.error == CainiaoServiceException.Kind.LOGIN_EXPIRED) return
        refresh.isEnabled = false
        if (force) refresh.text = "刷新中"
        activity.lifecycleScope.launch {
            try {
                try { CainiaoRenewal.online(activity, force) }
                catch (e: CainiaoServiceException) {
                    if (e.kind != CainiaoServiceException.Kind.REJECTED) throw e
                    if (CainiaoRenewal.refresh(activity, force = true) == null) throw e
                }
                CainiaoRenewal.refresh(activity)
            }
            catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) {
                if (force) android.widget.Toast.makeText(activity, e.kind.message, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                if (force) android.widget.Toast.makeText(activity, "身份码刷新失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                state = withContext(Dispatchers.IO) { runCatching { CainiaoRenewal.snapshot(activity) }.getOrDefault(state) }
                refresh.isEnabled = true; refresh.text = "刷新"; render()
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val display = runCatching { CainiaoIdentity.display(state.material, state.online, now) }.getOrNull()
        val code = display?.value
        barcode.visibility = if (code == null) View.GONE else View.VISIBLE
        codeText.visibility = barcode.visibility
        status.visibility = View.VISIBLE
        val seconds = ((display?.expiresAtMs ?: now) - now + 999).coerceAtLeast(0) / 1000
        val countdown = if (display == null) "" else String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        status.text = listOfNotNull(state.error?.message, countdown.takeIf { it.isNotEmpty() },
            "身份码暂不可用".takeIf { code == null && state.error == null }).joinToString("\n")
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
