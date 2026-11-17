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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.halo.expressassistant.api.CainiaoIdentity
import com.halo.expressassistant.api.CainiaoRenewal
import com.halo.expressassistant.api.CainiaoServiceException
import com.halo.expressassistant.api.TaobaoIdentity
import com.halo.expressassistant.data.BoundAccount
import com.halo.expressassistant.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Both providers remain visible and refresh independently. */
class IdentityPanel(private val activity: AppCompatActivity) {
    private val preferences = activity.getSharedPreferences("identity_display", android.content.Context.MODE_PRIVATE)
    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(24))
    }
    val view = ScrollView(activity).apply { isFillViewport = true; addView(body) }
    private val cainiao = CodeCard("菜鸟身份码") { updateCainiao(true) }
    private val taobao = CodeCard("淘宝身份码") { updateTaobao(true) }
    private val accountButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setSingleLine(true)
        setOnClickListener { chooseTaobaoAccount() }
    }
    private var state = CainiaoRenewal.Snapshot(null, null, 0, null)
    private var taobaoAccount: BoundAccount? = null
    private var taobaoCode: TaobaoIdentity.Code? = null
    private var taobaoError: TaobaoIdentity.Error? = null
    private var taobaoLastAttempt = 0L
    private var ticker: Job? = null
    private var visible = false

    init {
        taobao.content.addView(accountButton, 1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        body.addView(cainiao.view, LinearLayout.LayoutParams(-1, -2))
        body.addView(taobao.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    fun show() {
        visible = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        reloadFromStore()
        ticker?.cancel()
        ticker = activity.lifecycleScope.launch {
            while (visible) {
                render()
                val now = System.currentTimeMillis()
                if (state.material != null && (state.error != CainiaoServiceException.Kind.LOGIN_EXPIRED || state.canAutoRenew) &&
                    (state.online?.expiresAtMs ?: 0) <= now && now - state.lastAttemptAtMs >= 60_000) updateCainiao(false)
                if (taobaoAccount != null && taobaoError != TaobaoIdentity.Error.LOGIN_REQUIRED &&
                    taobaoCode?.usable(now) != true && now - taobaoLastAttempt >= 60_000) updateTaobao(false)
                delay(1000)
            }
        }
    }

    fun hide() {
        visible = false; ticker?.cancel(); ticker = null
        cainiao.cancel(); taobao.cancel()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun reloadFromStore() {
        syncTaobaoAccount()
        render()
        if (visible) updateTaobao(false)
        activity.lifecycleScope.launch {
            state = withContext(Dispatchers.IO) {
                runCatching { CainiaoRenewal.snapshot(activity) }
                    .getOrDefault(CainiaoRenewal.Snapshot(null, null, 0, CainiaoServiceException.Kind.INVALID_RESPONSE))
            }
            render()
            if (visible) updateCainiao(false)
        }
    }

    private fun syncTaobaoAccount() {
        val accounts = Store.accounts(activity, Store.CH_TAOBAO).filter { it.enabled }
        val selectedId = preferences.getString("taobao_account", "")
        val selected = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
        if (selected?.id != taobaoAccount?.id || selected?.payload != taobaoAccount?.payload) {
            taobao.cancel()
            taobaoCode = null; taobaoError = null; taobaoLastAttempt = 0
        }
        taobaoAccount = selected
        accountButton.text = selected?.label.orEmpty()
        accountButton.visibility = if (accounts.size > 1) View.VISIBLE else View.GONE
    }

    private fun chooseTaobaoAccount() {
        val accounts = Store.accounts(activity, Store.CH_TAOBAO).filter { it.enabled }
        if (accounts.size < 2) return
        MaterialAlertDialogBuilder(activity).setTitle("选择淘宝账号")
            .setSingleChoiceItems(accounts.map { it.label }.toTypedArray(), accounts.indexOfFirst { it.id == taobaoAccount?.id }) { dialog, index ->
                preferences.edit().putString("taobao_account", accounts[index].id).apply()
                dialog.dismiss(); syncTaobaoAccount(); render()
                if (visible) updateTaobao(false)
            }.setNegativeButton("取消", null).show()
    }

    private fun updateCainiao(force: Boolean) {
        if (cainiao.loading) return
        if (!force && state.error == CainiaoServiceException.Kind.LOGIN_EXPIRED && !state.canAutoRenew) return
        val version = cainiao.generation
        cainiao.setLoading(true)
        cainiao.request = activity.lifecycleScope.launch {
            try {
                try { CainiaoRenewal.online(activity, force) }
                catch (e: CainiaoServiceException) {
                    if (e.kind != CainiaoServiceException.Kind.REJECTED) throw e
                    if (CainiaoRenewal.refresh(activity, force = true) == null) throw e
                }
                CainiaoRenewal.refresh(activity)
            } catch (e: CancellationException) { throw e }
            catch (e: CainiaoServiceException) {
                if (force) android.widget.Toast.makeText(activity, e.kind.message, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                if (force) android.widget.Toast.makeText(activity, "菜鸟身份码刷新失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                if (version == cainiao.generation) {
                    state = withContext(Dispatchers.IO) { runCatching { CainiaoRenewal.snapshot(activity) }.getOrDefault(state) }
                    cainiao.setLoading(false); render()
                }
            }
        }
    }

    private fun updateTaobao(force: Boolean) {
        val account = taobaoAccount ?: return
        if (taobao.loading) return
        if (!force && (taobaoCode?.usable(System.currentTimeMillis()) == true || taobaoError == TaobaoIdentity.Error.LOGIN_REQUIRED)) return
        val version = taobao.generation
        taobaoLastAttempt = System.currentTimeMillis()
        taobao.setLoading(true)
        taobao.request = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { TaobaoIdentity.fetch(Store.cookieOf(account.payload)) }
                val current = Store.accountById(activity, Store.CH_TAOBAO, account.id)
                if (current?.enabled != true || current.payload != account.payload) {
                    syncTaobaoAccount(); render(); return@launch
                }
                if (version != taobao.generation) return@launch
                val refreshed = current.copy(payload = Store.cookiePayload(result.cookie))
                Store.updateAccount(activity, Store.CH_TAOBAO, refreshed)
                taobaoAccount = refreshed
                taobaoCode = result.code; taobaoError = null
            } catch (e: CancellationException) { throw e }
            catch (e: TaobaoIdentity.Failure) {
                taobaoError = e.error
                if (e.error == TaobaoIdentity.Error.LOGIN_REQUIRED) taobaoCode = null
                if (force) android.widget.Toast.makeText(activity, e.error.message, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                taobaoError = TaobaoIdentity.Error.INVALID_RESPONSE
            } finally {
                if (version == taobao.generation) { taobao.setLoading(false); render() }
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val cnDisplay = runCatching { CainiaoIdentity.display(state.material, state.online, now) }.getOrNull()
        cainiao.render(cnDisplay?.value, listOfNotNull(state.error?.message, cnDisplay?.let { countdown(it.expiresAtMs, now) }))
        val tbDisplay = taobaoCode?.takeIf { it.usable(now) }
        val tbError = if (taobaoAccount == null) "请先在设置中登录淘宝" else taobaoError?.message
        taobao.refresh.isEnabled = !taobao.loading && taobaoAccount != null
        taobao.render(tbDisplay?.value, listOfNotNull(tbError, tbDisplay?.let { countdown(it.refreshAtMs, now) }))
    }

    private fun countdown(next: Long, now: Long): String {
        val seconds = (next - now + 999).coerceAtLeast(0) / 1000
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private inner class CodeCard(title: String, onRefresh: () -> Unit) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val view = MaterialCardView(activity).apply {
            radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
        val refresh = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "刷新"; textSize = 14f
            contentDescription = "刷新$title"
            setIconResource(com.halo.expressassistant.R.drawable.ic_sync)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(6); iconSize = dp(18)
            gravity = Gravity.CENTER; minWidth = 0; minimumWidth = 0
            minHeight = dp(40); minimumHeight = dp(40)
            insetTop = 0; insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
            setOnClickListener { onRefresh() }
        }
        private val barcode = ImageView(activity).apply {
            contentDescription = "${title}条形码"; setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_XY; setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        private val codeText = TextView(activity).apply {
            textSize = 16f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        private val status = TextView(activity).apply {
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, 0)
        }
        private var previousCode = ""
        var generation = 0L
        var request: Job? = null
        var loading = false
            private set

        init {
            val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(activity).apply {
                text = title; textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(refresh, LinearLayout.LayoutParams(dp(104), dp(40)))
            content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            content.addView(barcode, LinearLayout.LayoutParams(-1, dp(120)))
            content.addView(codeText, LinearLayout.LayoutParams(-1, -2))
            content.addView(status, LinearLayout.LayoutParams(-1, -2))
        }

        fun setLoading(value: Boolean) {
            loading = value
            refresh.isEnabled = !value; refresh.text = if (value) "刷新中" else "刷新"
        }

        fun cancel() {
            generation++; request?.cancel(); request = null; setLoading(false)
        }

        fun render(code: String?, messages: List<String>) {
            barcode.visibility = if (code == null) View.GONE else View.VISIBLE
            codeText.visibility = barcode.visibility
            status.text = messages.joinToString("\n").ifBlank {
                if (loading) "正在刷新身份码" else "身份码暂不可用"
            }
            if (code == null) { barcode.setImageDrawable(null); codeText.text = ""; previousCode = ""; return }
            if (code == previousCode) return
            val matrix = MultiFormatWriter().encode(code, BarcodeFormat.CODE_128, 1200, 360, mapOf(EncodeHintType.MARGIN to 24))
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
            bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            barcode.setImageBitmap(bitmap); codeText.text = code; previousCode = code
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
