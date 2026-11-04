package com.halo.expressassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.halo.expressassistant.R
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var addressInput: EditText? = null
    private val requestXiaomi = 100
    private val requestJd = 101
    private val requestTb = 102
    private val requestPdd = 103

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        Paper.apply(this, binding.root, binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rowTheme.setOnClickListener { showThemeSheet() }
        binding.rowAddress.setOnClickListener { showAddressSheet() }
        binding.rowXiaomiLogin.setOnClickListener { showXiaomiSheet() }
        binding.rowJdLogin.setOnClickListener { showJdSheet() }
        binding.rowTbLogin.setOnClickListener { showTbSheet() }
        binding.rowPddLogin.setOnClickListener { showPddSheet() }
        binding.rowHidden.setOnClickListener { showHiddenDialog() }
        binding.rowWidget.setOnClickListener { showWidgetSheet() }
        binding.rowPoll.setOnClickListener { showPollSheet() }
        binding.rowUpdate.setOnClickListener { showUpdateSheet() }
        binding.rowMore.setOnClickListener { showMoreSheet() }
        binding.rowAbout.setOnClickListener { showAboutSheet() }
        binding.rowSupport.setOnClickListener { showSupportDialog() }
        refreshButtons()
    }

    /** 打开当前维护仓库。 */
    private fun showSupportDialog() {
        openUrl("https://github.com/AlexbeatsZ/ExpressAssistant")
    }

    private fun refreshButtons() {
        binding.themeSummary.text = themeLabel()
        binding.xiaomiLoginSummary.text = accountSummary(Store.CH_XIAOMI, "小米")
        binding.jdLoginSummary.text = accountSummary(Store.CH_JD, "京东")
        binding.tbLoginSummary.text = accountSummary(Store.CH_TAOBAO, "淘宝")
        binding.pddLoginSummary.text = accountSummary(Store.CH_PDD, "拼多多")
        binding.hiddenSummary.text = "${Store.xiaomiHidden(this).size} 个已删除"
        val poll = Store.pollIntervalMin(this)
        binding.pollSummary.text = if (poll <= 0) "默认关闭" else "每 $poll 分钟 · 仅轮询有在途跟踪件的平台"
        binding.addressSummary.text = addressSummaryLabel()
    }

    /** 清除小米域 WebView Cookie（被 removeAllCookies 取代；保留兼容引用） */
    private fun clearXiaomiCookies() {
        val cm = android.webkit.CookieManager.getInstance()
        for (host in listOf(
            "https://account.xiaomi.com",
            "https://api.assistant.miui.com",
            "https://i.mi.com",
            "https://passport.xiaomi.com"
        )) {
            val cookie = cm.getCookie(host) ?: continue
            for (part in cookie.split(";")) {
                val k = part.trim().split("=", limit = 2).getOrNull(0)?.trim() ?: continue
                if (k.isEmpty()) continue
                cm.setCookie(host, "$k=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
            }
        }
        cm.flush()
    }

    private fun addressSummaryLabel(): String {
        val list = Store.addresses(this)
        if (list.isEmpty()) return "未设置"
        val activeId = Store.activeAddressId(this)
        val a = list.firstOrNull { it.id == activeId } ?: list.first()
        return "${a.label} · ${a.address.take(14)}"
    }

    private fun accountSummary(channel: String, name: String): String {
        val list = Store.accounts(this, channel)
        if (list.isEmpty()) return "未绑定"
        val enabled = list.count { it.enabled }
        val first = list.firstOrNull { it.enabled } ?: list.first()
        val tail = if (enabled != list.size) "（启用 $enabled/${list.size}）" else ""
        return "已绑定 ${list.size} 个 · ${first.label.take(16)}$tail"
    }

    private fun themeLabel(): String = when (Store.theme(this)) {
        Themes.MONET -> "莫奈取色"
        Themes.CUSTOM -> {
            if (Store.customSeparate(this)) {
                "自定义 · 分昼夜设置"
            } else {
                val color = if (Store.colorScheme(this) == "monet") "莫奈" else "温暖"
                val font = if (Store.themeFont(this) == Themes.FONT_SERIF) "衬线" else "无衬线"
                "自定义 · $color / $font / 纸感 ${Store.paperIntensity(this)}%"
            }
        }
        else -> "温暖纸感"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestXiaomi || requestCode == requestJd || requestCode == requestTb || requestCode == requestPdd) {
            refreshButtons()
        }
    }


    /* ─────────────── 主题外观 ─────────────── */

    private fun showThemeSheet() {
        val (sheet, container) = Sheets.create(this, "主题外观", "选择主题，或在自定义里自由混搭元素")
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(body)
        var changed = false
        sheet.setOnDismissListener {
            if (changed) recreate()
        }
        renderThemeBody(sheet, body) { changed = true }
        sheet.show()
    }

    private fun renderThemeBody(
        sheet: BottomSheetDialog,
        body: LinearLayout,
        markChanged: () -> Unit = {}
    ) {
        body.removeAllViews()
        val theme = Store.theme(this)
        body.addView(
            Sheets.optionRow(this, "莫奈取色", "跟随系统壁纸 · 无衬线 · 无纸纹", theme == Themes.MONET) {
                Store.saveTheme(this@SettingsActivity, Themes.MONET)
                Store.saveColorScheme(this@SettingsActivity, "monet")
                Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SANS)
                Store.savePaperIntensity(this@SettingsActivity, 0)
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "温暖纸感", "温暖配色 · 衬线 · 纸纹", theme == Themes.LARK) {
                Store.saveTheme(this@SettingsActivity, Themes.LARK)
                Store.saveColorScheme(this@SettingsActivity, "warm")
                Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SERIF)
                Store.savePaperIntensity(this@SettingsActivity, 100)
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "自定义", "混搭莫奈 / 温暖、衬线 / 无衬线、纸感", theme == Themes.CUSTOM) {
                Store.saveTheme(this@SettingsActivity, Themes.CUSTOM)
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        if (theme == Themes.CUSTOM) {
            renderCustomTheme(sheet, body, markChanged)
        }
    }

    private fun renderCustomTheme(
        sheet: BottomSheetDialog,
        body: LinearLayout,
        markChanged: () -> Unit
    ) {
        body.addView(Sheets.sectionTitle(this, "昼夜设置"))
        body.addView(
            Sheets.optionRow(this, "白天 / 黑夜分别设置", "开启后可为白天和夜晚分别搭配", Store.customSeparate(this)) {
                Store.saveCustomSeparate(
                    this@SettingsActivity,
                    !Store.customSeparate(this@SettingsActivity)
                )
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        if (Store.customSeparate(this)) {
            renderThemeMode(sheet, body, markChanged, "白天", true)
            renderThemeMode(sheet, body, markChanged, "夜间", false)
        } else {
            renderThemeMode(sheet, body, markChanged, null, null)
        }
    }

    private fun renderThemeMode(
        sheet: BottomSheetDialog,
        body: LinearLayout,
        markChanged: () -> Unit,
        label: String?,
        isDay: Boolean?
    ) {
        if (label != null) {
            body.addView(Sheets.sectionTitle(this, label))
        }
        val color = when (isDay) {
            true -> Store.colorSchemeDay(this)
            false -> Store.colorSchemeNight(this)
            null -> Store.colorScheme(this)
        }
        val font = when (isDay) {
            true -> Store.themeFontDay(this)
            false -> Store.themeFontNight(this)
            null -> Store.themeFont(this)
        }
        val paper = when (isDay) {
            true -> Store.paperIntensityDay(this)
            false -> Store.paperIntensityNight(this)
            null -> Store.paperIntensity(this)
        }

        body.addView(
            Sheets.optionRow(this, "配色 · 莫奈取色", null, color == "monet") {
                when (isDay) {
                    true -> Store.saveColorSchemeDay(this@SettingsActivity, "monet")
                    false -> Store.saveColorSchemeNight(this@SettingsActivity, "monet")
                    null -> Store.saveColorScheme(this@SettingsActivity, "monet")
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "配色 · 温暖", null, color == "warm") {
                when (isDay) {
                    true -> Store.saveColorSchemeDay(this@SettingsActivity, "warm")
                    false -> Store.saveColorSchemeNight(this@SettingsActivity, "warm")
                    null -> Store.saveColorScheme(this@SettingsActivity, "warm")
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "字体 · 衬线", null, font == Themes.FONT_SERIF) {
                when (isDay) {
                    true -> Store.saveThemeFontDay(this@SettingsActivity, Themes.FONT_SERIF)
                    false -> Store.saveThemeFontNight(this@SettingsActivity, Themes.FONT_SERIF)
                    null -> Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SERIF)
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )
        body.addView(
            Sheets.optionRow(this, "字体 · 无衬线", null, font == Themes.FONT_SANS) {
                when (isDay) {
                    true -> Store.saveThemeFontDay(this@SettingsActivity, Themes.FONT_SANS)
                    false -> Store.saveThemeFontNight(this@SettingsActivity, Themes.FONT_SANS)
                    null -> Store.saveThemeFont(this@SettingsActivity, Themes.FONT_SANS)
                }
                markChanged()
                renderThemeBody(sheet, body, markChanged)
            }
        )

        body.addView(Sheets.sectionTitle(this, "纸感强度"))
        val valueText = TextView(this).apply {
            text = "$paper%"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MaterialColors.getColor(this@SettingsActivity, android.R.attr.colorPrimary, 0))
            gravity = Gravity.END
        }
        body.addView(valueText)
        body.addView(
            SeekBar(this).apply {
                max = 200
                progress = paper
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        when (isDay) {
                            true -> Store.savePaperIntensityDay(this@SettingsActivity, progress)
                            false -> Store.savePaperIntensityNight(this@SettingsActivity, progress)
                            null -> Store.savePaperIntensity(this@SettingsActivity, progress)
                        }
                        valueText.text = "$progress%"
                        markChanged()
                        Paper.apply(this@SettingsActivity, binding.root, binding.toolbar)
                        refreshButtons()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
        )
        body.addView(
            TextView(this).apply {
                text = "0 为关闭纸纹"
                textSize = 12f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, 0)
            }
        )
    }

    /* ─────────────── 小组件 ─────────────── */

    private fun showWidgetSheet() {
        val (sheet, container) = Sheets.create(this, "小组件", "选择显示哪些状态的快递，已完成和异常固定不显示")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(box)

        fun render() {
            box.removeAllViews()
            fun add(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
                box.addView(
                    Sheets.optionRow(this@SettingsActivity, title, null, checked) {
                        onChange(!checked)
                        render()
                        refreshButtons()
                    }
                )
            }
            add("派送中", Store.widgetShowDelivering(this@SettingsActivity)) {
                Store.saveWidgetShowDelivering(this@SettingsActivity, it)
            }
            add("运输中", Store.widgetShowShipped(this@SettingsActivity)) {
                Store.saveWidgetShowShipped(this@SettingsActivity, it)
            }
            add("未发货", Store.widgetShowNotShipped(this@SettingsActivity)) {
                Store.saveWidgetShowNotShipped(this@SettingsActivity, it)
            }
        }
        render()
        sheet.show()
    }

    /* ─────────────── 更多连接方式 ─────────────── */

    private fun showMoreSheet() {
        val (sheet, container) = Sheets.create(this, "更多连接方式", "快递100 兜底与无障碍导入")
        val kdKey = outlinedInput("快递100 key（可留空）", Store.kdKey(this))
        val kdCustomer = outlinedInput("快递100 customer（可留空）", Store.kdCustomer(this))
        container.addView(kdKey)
        container.addView(kdCustomer)
        val swKd = SwitchMaterial(this).apply {
            isChecked = Store.kd100Fallback(this@SettingsActivity)
            text = "快递100兜底"
        }
        val swA11y = SwitchMaterial(this).apply {
            isChecked = Store.accessibilityEnabled(this@SettingsActivity)
            text = "无障碍导入（小米/菜鸟/淘宝）"
        }
        container.addView(swKd)
        container.addView(swA11y)
        container.addView(
            MaterialButton(this).apply {
                text = "保存"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    Store.saveConnectionSettings(
                        this@SettingsActivity,
                        kdKey.editText?.text?.toString()?.trim().orEmpty(),
                        kdCustomer.editText?.text?.toString()?.trim().orEmpty(),
                        swKd.isChecked,
                        swA11y.isChecked
                    )
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
            }
        )
        sheet.show()
    }

    /* ─────────────── 关于云雀 ─────────────── */

    private fun showAboutSheet() {
        val (sheet, container) = Sheets.create(this, "关于快递助手")
        container.addView(TextView(this).apply {
            text = "快递助手 v${UpdateChecker.versionName(this@SettingsActivity)}\nAlexbeatsZ/ExpressAssistant"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(20))
        })
        sheet.show()
    }

    /* ─────────────── 更新与 GitHub ─────────────── */

    private fun showUpdateSheet() {
        val (sheet, container) = Sheets.create(this, "更新与 GitHub")
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} · versionCode ${PackageInfoCompat.getLongVersionCode(info)}"
        } catch (e: Throwable) {
            "未知"
        }
        container.addView(Sheets.sectionTitle(this, "当前版本"))
        container.addView(
            TextView(this).apply {
                text = version
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 2, 0, dp(8))
            }
        )
        container.addView(Sheets.divider(this))
        container.addView(
            sheetNavRow(
                "GitHub 仓库",
                "AlexbeatsZ/ExpressAssistant",
                R.drawable.ic_widget_open
            ) {
                openUrl("https://github.com/AlexbeatsZ/ExpressAssistant")
            }
        )
        container.addView(Sheets.divider(this))
        val checkButton = MaterialButton(this).apply {
            text = "检查更新"
            isEnabled = true
        }
        container.addView(
            checkButton,
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        checkButton.setOnClickListener {
            checkButton.isEnabled = false
            checkButton.text = "检查中…"
            CoroutineScope(Dispatchers.Main).launch {
                val result = UpdateChecker.check(this@SettingsActivity)
                checkButton.isEnabled = true
                checkButton.text = "检查更新"
                when {
                    result.hasUpdate -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "发现新版本 v${result.latestTag}，正在打开下载…",
                            Toast.LENGTH_LONG
                        ).show()
                        sheet.dismiss()
                        openUrl(result.downloadUrl ?: "https://github.com/AlexbeatsZ/ExpressAssistant/releases/latest")
                    }
                    result.latestTag != null -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "已是最新版本 v${result.latestTag}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            "检查失败：${result.error ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        sheet.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    /* ─────────────── 多源绑定：平台账号列表（可绑定任意数量） ─────────────── */

    private fun showJdSheet() = showAccountSheet(
        Store.CH_JD, "京东登录", "登录在 App 内完成，凭证仅保存在本地；绑定后京东件与商品溯源均可同步。",
        JdLoginActivity::class.java, requestJd
    )

    private fun showTbSheet() = showAccountSheet(
        Store.CH_TAOBAO, "淘宝登录", "登录在 App 内完成，凭证仅保存在本地；绑定后淘宝件与菜鸟溯源均可同步。",
        TbLoginActivity::class.java, requestTb
    )

    private fun showPddSheet() = showAccountSheet(
        Store.CH_PDD, "拼多多登录", "使用手机号验证码登录独立网页会话，验证订单访问后绑定；凭证仅保存在本地。",
        PddLoginActivity::class.java, requestPdd,
        loginWarning = "本机已实测：拼多多网页登录会使官方 App 退出登录，官方 App 重新登录也会使网页会话失效。需要保留官方 App 登录时，请暂不绑定网页会话。仍要使用短信验证码登录网页？"
    )

    private fun showXiaomiSheet() = showAccountSheet(
        Store.CH_XIAOMI, "小米登录", "扫码登录后同步 · 凭证仅保存在本地；可绑定多个小米账号。",
        XiaomiLoginActivity::class.java, requestXiaomi
    )

    private fun showAccountSheet(
        channel: String,
        title: String,
        hint: String,
        loginActivity: Class<*>,
        requestCode: Int,
        loginWarning: String? = null
    ) {
        val (sheet, container) = Sheets.create(this, title)
        container.addView(
            TextView(this).apply {
                text = hint
                textSize = 14f
                setLineSpacing(0f, 1.3f)
                setTextColor(onSurfaceVariant())
                setPadding(0, 0, 0, dp(14))
            }
        )
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list)

        fun render() {
            list.removeAllViews()
            val accounts = Store.accounts(this@SettingsActivity, channel)
            if (accounts.isEmpty()) {
                list.addView(TextView(this@SettingsActivity).apply {
                    text = "还没有绑定账号，点下面按钮登录并绑定第一个账号"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(2), 0, dp(8))
                })
            }
            for (a in accounts) {
                val card = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                // 第一行：账号名 + 状态（占满宽度）| 启停开关
                val name = TextView(this@SettingsActivity).apply {
                    text = if (a.label.isBlank()) "账号" else a.label
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(MaterialColors.getColor(this@SettingsActivity, android.R.attr.textColorPrimary, 0))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val sub = TextView(this@SettingsActivity).apply {
                    val phones = if (channel == Store.CH_XIAOMI) Store.parseXiaomiCred(a.payload).phones else emptyList()
                    text = when {
                        !a.enabled -> "已停用"
                        channel == Store.CH_XIAOMI && phones.isNotEmpty() -> "已绑手机号：" + phones.joinToString("、") { Store.maskPhone(it) }
                        else -> "同步中"
                    }
                    textSize = 12f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(name)
                texts.addView(sub)
                val topRow = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                topRow.addView(SwitchMaterial(this).apply {
                    isChecked = a.enabled
                    setOnCheckedChangeListener { _, checked ->
                        Store.updateAccount(this@SettingsActivity, channel, a.copy(enabled = checked))
                        refreshButtons()
                        render()
                    }
                })
                card.addView(topRow)
                // 第二行：操作按钮靠右一排（小按钮）
                val ops = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(4), 0, 0)
                }
                fun opBtn(text: String, danger: Boolean = false, onClick: () -> Unit) {
                    ops.addView(
                        MaterialButton(this@SettingsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            this.text = text
                            textSize = 12f
                            setPadding(dp(10), 0, dp(10), 0)
                            if (danger) setTextColor(Color.rgb(179, 38, 30))
                            setOnClickListener { onClick() }
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(6) }
                    )
                }
                opBtn("改名") { showRenameAccount(channel, a) { render() } }
                // 手机号绑定入口已按需移除（小米渠道可选功能；已有绑定数据仍生效）
                opBtn("移除", danger = true) {
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("移除绑定账号")
                        .setMessage("确认移除「${a.label}」？该账号的快递件将不再同步。")
                        .setPositiveButton("移除") { _, _ ->
                            Store.removeAccount(this@SettingsActivity, channel, a.id)
                            // 域级 cookie（.taobao.com/.jd.com 等）按 host expire 删不掉，统一真删全部 WebView Cookie（Store 凭证不受影响，同步时重新注入）
                            if (channel == Store.CH_PDD) {
                                val profile = org.json.JSONObject(a.payload).optString("webProfile")
                                if (PddWeb.isolated) PddWeb.clearProfile(profile)
                                else PddCapture.clearCookies()
                            } else {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.CookieManager.getInstance().flush()
                            }
                            refreshButtons()
                            render()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                card.addView(ops)
                list.addView(card)
            }
            list.addView(
                MaterialButton(this).apply {
                    text = "＋ 绑定新账号"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    setOnClickListener {
                        fun go() {
                            sheet.dismiss()
                            startActivityForResult(
                                Intent(this@SettingsActivity, loginActivity),
                                requestCode
                            )
                        }
                        if (loginWarning == null) {
                            go()
                        } else {
                            MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("登录提示")
                                .setMessage(loginWarning)
                                .setPositiveButton("继续登录") { _, _ -> go() }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
            )
        }
        render()
        sheet.show()
    }

    private fun showRenameAccount(
        channel: String,
        account: com.halo.expressassistant.data.BoundAccount,
        onDone: () -> Unit
    ) {
        val edit = EditText(this).apply {
            setText(account.label)
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("绑定账号名称")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val n = edit.text?.toString()?.trim().orEmpty()
                Store.updateAccount(this, channel, account.copy(label = if (n.isNotEmpty()) n else account.label))
                refreshButtons()
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTaobaoSheet() {
        val (sheet, container) = Sheets.create(this, "淘宝快递同步")
        container.addView(
            TextView(this).apply {
                text = "若要同步淘宝的快递，需要在淘宝设置的隐私设置里关闭“订单号码保护”。"
                textSize = 14f
                setLineSpacing(0f, 1.3f)
                setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOnSurface, 0))
                setPadding(0, 0, 0, dp(16))
            }
        )
        container.addView(
            MaterialButton(this).apply {
                text = "好的"
                setOnClickListener { sheet.dismiss() }
            }
        )
        sheet.show()
    }

    /* ─────────────── 我的地址（多地址：新增/编辑/删除/切换当前） ─────────────── */

    private fun showAddressSheet() {
        val (sheet, container) = Sheets.create(this, "我的地址")
        container.addView(
            TextView(this).apply {
                text = ""
                textSize = 13f
                setLineSpacing(0f, 1.25f)
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(14))
            }
        )
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listBox)

        fun render() {
            listBox.removeAllViews()
            val addrs = Store.addresses(this@SettingsActivity)
            if (addrs.isEmpty()) {
                listBox.addView(TextView(this@SettingsActivity).apply {
                    text = "还没有地址，点下面新增一个"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(2), 0, dp(8))
                })
            }
            val activeId = Store.activeAddressId(this@SettingsActivity)
            for (a in addrs) {
                val isActive = (activeId == a.id) || (activeId.isBlank() && addrs.first().id == a.id)
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    setBackgroundResource(selectableBackground())
                    isClickable = true
                    setOnClickListener {
                        Store.setActiveAddressId(this@SettingsActivity, a.id)
                        refreshButtons()
                        render()
                    }
                }
                val title = TextView(this@SettingsActivity).apply {
                    text = a.label + if (isActive) "　● 当前" else ""
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(
                        if (isActive) MaterialColors.getColor(this@SettingsActivity, android.R.attr.colorPrimary, 0)
                        else MaterialColors.getColor(this@SettingsActivity, android.R.attr.textColorPrimary, 0)
                    )
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val body = TextView(this@SettingsActivity).apply {
                    text = a.address
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                }
                row.addView(title)
                row.addView(body)
                val ops = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(4), 0, 0)
                }
                ops.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "编辑"
                        textSize = 12f
                        setPadding(dp(10), 0, dp(10), 0)
                        setOnClickListener { showEditAddress(a) { render() } }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(6) }
                )
                ops.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "删除"
                        textSize = 12f
                        setPadding(dp(10), 0, dp(10), 0)
                        setTextColor(Color.rgb(179, 38, 30))
                        setOnClickListener {
                            MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("删除地址")
                                .setMessage("确认删除「${a.label}」？使用该地址的快递会回退到全局当前地址。")
                                .setPositiveButton("删除") { _, _ ->
                                    Store.removeAddress(this@SettingsActivity, a.id)
                                    refreshButtons()
                                    render()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(6) }
                )
                row.addView(ops)
                listBox.addView(row)
            }
            listBox.addView(
                MaterialButton(this).apply {
                    text = "＋ 新增地址"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    setOnClickListener { showEditAddress(null) { render() } }
                }
            )
        }
        render()
        sheet.show()
    }

    private fun showEditAddress(existing: com.halo.expressassistant.data.HomeAddress?, onDone: () -> Unit) {
        val (sheet, container) = Sheets.create(this, if (existing == null) "新增地址" else "编辑地址 · ${existing.label}")
        val labelInput = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        labelInput.hint = "名称（如 家 / 公司）"
        labelInput.editText?.setText(existing?.label ?: "默认地址")
        container.addView(labelInput)
        val inputLayout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        inputLayout.hint = "收件地址"
        inputLayout.editText?.setText(existing?.address ?: "")
        container.addView(inputLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "自动定位"
                setIconResource(R.drawable.ic_location)
                setOnClickListener {
                    locateAddress(inputLayout.editText!!) {
                        Toast.makeText(this@SettingsActivity, "定位失败，请检查定位权限或手动填写", Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )
        buttons.addView(
            MaterialButton(this).apply {
                text = "保存"
                setOnClickListener {
                    val addr = inputLayout.editText?.text?.toString()?.trim().orEmpty()
                    if (addr.isEmpty()) {
                        Toast.makeText(this@SettingsActivity, "请填写收件地址", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val label = labelInput.editText?.text?.toString()?.trim().orEmpty().ifEmpty { "默认地址" }
                    if (existing == null) {
                        val a = Store.addAddress(this@SettingsActivity, label, addr)
                        if (Store.activeAddressId(this@SettingsActivity).isBlank()) {
                            Store.setActiveAddressId(this@SettingsActivity, a.id)
                        }
                    } else {
                        Store.updateAddress(this@SettingsActivity, existing.id, label, addr)
                    }
                    refreshButtons()
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                    onDone()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.show()
    }

    private fun locateAddress(input: EditText, onFail: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val address = withContext(Dispatchers.IO) {
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: return@withContext ""
                    val geocoder = Geocoder(this@SettingsActivity, Locale.CHINA)
                    val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    val a = list?.firstOrNull()
                    listOf(
                        a?.countryName,
                        a?.adminArea,
                        a?.locality,
                        a?.subLocality,
                        a?.thoroughfare,
                        a?.subThoroughfare
                    ).filterNotNull().joinToString("").ifBlank {
                        "${loc.latitude},${loc.longitude}"
                    }
                } catch (e: Throwable) {
                    ""
                }
            }
            if (address.isNotEmpty()) {
                input.setText(address)
            } else {
                onFail()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val input = addressInput ?: return
            locateAddress(input) {
                Toast.makeText(this, "定位失败，请检查定位权限或手动填写", Toast.LENGTH_LONG).show()
            }
        }
    }

    /* ─────────────── 本地接口 ─────────────── */

    /* ─────────────── 手机号管理（并入小米登录：按指定账号） ─────────────── */

    private fun showPhoneDialogFor(account: com.halo.expressassistant.data.BoundAccount?) {
        val cred = account?.let { Store.parseXiaomiCred(it.payload) } ?: Store.xiaomiCred(this)
        val targetId = account?.id ?: Store.firstEnabledAccount(this, Store.CH_XIAOMI)?.id ?: ""
        if (cred.token.isEmpty() || cred.cUser.isEmpty()) {
            Toast.makeText(this, "请先在小米登录面板完成扫码登录", Toast.LENGTH_LONG).show()
            return
        }
        val (sheet, container) = Sheets.create(this, "手机号管理 · ${account?.label ?: "小米账号"}")
        val phoneList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(phoneList)

        fun authParams(): Array<String?> = arrayOf(
            cred.token,
            cred.cUser,
            cred.accountId,
            cred.oaid,
            cred.vaid
        )

        /** 渲染已绑手机号列表（每个可解绑 = 多手机号管理） */
        fun renderPhones() {
            phoneList.removeAllViews()
            val current = Store.parseXiaomiCred(
                (account?.let { Store.accountById(this@SettingsActivity, Store.CH_XIAOMI, it.id) }
                    ?: Store.firstEnabledAccount(this@SettingsActivity, Store.CH_XIAOMI))?.payload.orEmpty()
            ).phones
            if (current.isEmpty()) {
                phoneList.addView(TextView(this@SettingsActivity).apply {
                    text = "当前绑定：（无）—— 绑定一个手机号后即可同步该号码名下的快递"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(2), 0, dp(10))
                })
                return
            }
            phoneList.addView(
                TextView(this@SettingsActivity).apply {
                    text = "当前绑定（${current.size} 个，最多同步这些手机号的快递）："
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                    setPadding(0, dp(2), 0, dp(4))
                }
            )
            for (p in current) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = Store.maskPhone(p)
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(MaterialColors.getColor(this@SettingsActivity, android.R.attr.textColorPrimary, 0))
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                row.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "解绑"
                        textSize = 12f
                        setPadding(dp(10), 0, dp(10), 0)
                        setTextColor(Color.rgb(179, 38, 30))
                        setOnClickListener {
                            MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("解绑手机号")
                                .setMessage("确认解绑 ${Store.maskPhone(p)}？该号码名下的快递将不再同步。")
                                .setPositiveButton("解绑") { _, _ ->
                                    CoroutineScope(Dispatchers.Main).launch {
                                        try {
                                            val a = authParams()
                                            val remaining = current.filterNot { it == p }
                                            val raw = withContext(Dispatchers.IO) {
                                                XiaomiApi.bindPhone(
                                                    this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], p, remaining, false
                                                )
                                            }
                                            if (JSONObject(raw).optInt("code") == 0) {
                                                Store.updateXiaomiPhones(this@SettingsActivity, targetId, remaining)
                                                Toast.makeText(this@SettingsActivity, "已解绑", Toast.LENGTH_SHORT).show()
                                                refreshButtons()
                                                renderPhones()
                                            } else {
                                                Toast.makeText(this@SettingsActivity, "解绑失败：" + JSONObject(raw).optString("message"), Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: Throwable) {
                                            Toast.makeText(this@SettingsActivity, "解绑异常：$e", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                )
                phoneList.addView(row)
            }
        }
        renderPhones()

        val phoneInput = outlinedInput("绑定新的手机号", number = true)
        container.addView(phoneInput)
        val sendBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "发送验证码"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        container.addView(sendBtn)
        val codeInput = outlinedInput("短信验证码", number = true)
        container.addView(
            codeInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        val bindBtn = MaterialButton(this).apply {
            text = "确认绑定"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        container.addView(bindBtn)
        sheet.show()

        sendBtn.setOnClickListener {
            val phone = phoneInput.editText?.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty()) {
                Toast.makeText(this, "请输入手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val a = authParams()
                    val raw = withContext(Dispatchers.IO) {
                        XiaomiApi.sendVerificationCode(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone
                        )
                    }
                    val code = JSONObject(raw).optInt("code")
                    val msg = if (code == 0) "验证码已发送" else JSONObject(raw).optString("message")
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    Toast.makeText(this@SettingsActivity, "发送失败：$e", Toast.LENGTH_LONG).show()
                } finally {
                    sendBtn.isEnabled = true
                }
            }
        }

        bindBtn.setOnClickListener {
            val phone = phoneInput.editText?.text?.toString()?.trim().orEmpty()
            val code = codeInput.editText?.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || code.isEmpty()) {
                Toast.makeText(this, "请填写手机号和验证码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bindBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val a = authParams()
                    val checkRaw = withContext(Dispatchers.IO) {
                        XiaomiApi.checkVerificationCode(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone, code
                        )
                    }
                    if (JSONObject(checkRaw).optInt("code") != 0) {
                        Toast.makeText(this@SettingsActivity, "验证码校验失败", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val phones = (cred.phones + phone).distinct()
                    val bindRaw = withContext(Dispatchers.IO) {
                        XiaomiApi.bindPhone(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone, phones, true
                        )
                    }
                    if (JSONObject(bindRaw).optInt("code") == 0) {
                        // 多源绑定：写给这个账号的 payload（不再是已弃用的旧键）
                        Store.updateXiaomiPhones(this@SettingsActivity, targetId, phones)
                        Toast.makeText(this@SettingsActivity, "绑定成功", Toast.LENGTH_LONG).show()
                        refreshButtons()
                        sheet.dismiss()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "绑定失败：" + JSONObject(bindRaw).optString("message"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Throwable) {
                    Toast.makeText(this@SettingsActivity, "绑定异常：$e", Toast.LENGTH_LONG).show()
                } finally {
                    bindBtn.isEnabled = true
                }
            }
        }
    }

    private fun showHiddenDialog() {
        val hidden = Store.xiaomiHidden(this)
        val (sheet, container) = Sheets.create(this, "删除快递")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val orphanHidden = hidden.filter { Store.accountForItem(this@SettingsActivity, it) == null }

        fun refresh() {
            list.removeAllViews()
            val items = Store.xiaomiHidden(this@SettingsActivity)
            for (item in items) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setBackgroundResource(selectableBackground())
                    isClickable = true
                    setOnClickListener {
                        val current = Store.items(this@SettingsActivity).toMutableList()
                        if (current.none { it.mailNo == item.mailNo }) current.add(item)
                        Store.saveItems(this@SettingsActivity, current)
                        Store.restoreHidden(this@SettingsActivity, item.mailNo)
                        refreshButtons()
                        Toast.makeText(this@SettingsActivity, "已恢复 ${item.companyName}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = item.companyName
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                )
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = item.mailNo
                        textSize = 13f
                        setTextColor(onSurfaceVariant())
                    }
                )
                list.addView(row)
            }
        }

        // 功能区一（上方）：清除「已移除账号」的删除记录
        container.addView(
            MaterialButton(this).apply {
                text = if (orphanHidden.isEmpty()) "清除已移除账号的快递" else "清除已移除账号的快递（${orphanHidden.size}）"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isEnabled = orphanHidden.isNotEmpty()
                setOnClickListener {
                    val orphans = Store.xiaomiHidden(this@SettingsActivity)
                        .filter { Store.accountForItem(this@SettingsActivity, it) == null }
                    if (orphans.isEmpty()) return@setOnClickListener
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("清除已移除账号的快递")
                        .setMessage("将永久清除 ${orphans.size} 条删除记录（其绑定账号已移除，无法恢复）。")
                        .setPositiveButton("清除") { _, _ ->
                            Store.removeHiddenItems(
                                this@SettingsActivity,
                                orphans.map { it.mailNo }.toSet()
                            )
                            refreshButtons()
                            Toast.makeText(this@SettingsActivity, "已清除 ${orphans.size} 条", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        )
        // 功能区二（下方）：删除的快递列表 + 标题
        container.addView(
            TextView(this).apply {
                text = "删除的快递" + if (hidden.isEmpty()) "" else "（${hidden.size}）"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MaterialColors.getColor(this@SettingsActivity, android.R.attr.textColorPrimary, 0))
                setPadding(0, dp(14), 0, dp(4))
            }
        )
        container.addView(
            TextView(this).apply {
                text = if (hidden.isEmpty()) "暂无删除记录" else "点击可恢复，或使用上方按钮清除已移除账号的记录"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 0, 0, dp(8))
            }
        )
        container.addView(list)
        refresh()

        if (hidden.isNotEmpty()) {
            container.addView(
                MaterialButton(this).apply {
                    text = "全部恢复"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    setOnClickListener {
                        val current = Store.items(this@SettingsActivity).toMutableList()
                        for (item in Store.xiaomiHidden(this@SettingsActivity)) {
                            if (current.none { it.mailNo == item.mailNo }) current.add(item)
                        }
                        Store.saveItems(this@SettingsActivity, current)
                        Store.clearHidden(this@SettingsActivity)
                        refreshButtons()
                        Toast.makeText(this@SettingsActivity, "已全部恢复", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            )
        }

        sheet.show()
    }

    /** 快递轮询设置：输入分钟（0=关闭）；开启跟踪会自动默认 15 分钟 */
    private fun showPollSheet() {
        val (sheet, container) = Sheets.create(this, "快递轮询")
        container.addView(
            TextView(this).apply {
                text = "输入轮询间隔（分钟），0 表示关闭。\n" +
                    "仅轮询「有在途且已开启跟踪」的快递所属平台；\n" +
                    "跟踪件进入完成/异常（或关闭跟踪）后自动停止对应平台轮询；\n" +
                    "默认不开启——开启任意快递跟踪后自动默认 15 分钟。"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(12))
            }
        )
        val input = layoutInflater.inflate(com.halo.expressassistant.R.layout.view_input_outlined, null) as TextInputLayout
        input.hint = "分钟（0 = 关闭）"
        input.editText?.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.editText?.setText(Store.pollIntervalMin(this).toString())
        container.addView(input)
        container.addView(
            MaterialButton(this).apply {
                text = "保存"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
                setOnClickListener {
                    val min = input.editText?.text?.toString()?.trim()?.toIntOrNull() ?: 0
                    Store.savePollIntervalMin(this@SettingsActivity, min)
                    refreshButtons()
                    sheet.dismiss()
                    android.widget.Toast.makeText(
                        this@SettingsActivity,
                        if (min <= 0) "已关闭快递轮询" else "轮询间隔：每 $min 分钟（系统可能有 1-3 分钟误差）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        sheet.show()
    }

    /* ─────────────── 通用小工具 ─────────────── */

    private fun sheetNavRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setBackgroundResource(selectableBackground())
            isClickable = true
            isFocusable = true

            addView(
                ImageView(this@SettingsActivity).apply {
                    setImageResource(icon)
                    setColorFilter(MaterialColors.getColor(this@SettingsActivity, android.R.attr.colorPrimary, 0))
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) }
                }
            )
            val texts = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(
                TextView(this@SettingsActivity).apply {
                    text = title
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
            texts.addView(
                TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(onSurfaceVariant())
                }
            )
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                TextView(this@SettingsActivity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOutline, 0))
                }
            )
            setOnClickListener { onClick() }
        }



    private fun outlinedInput(
        hint: String,
        text: String = "",
        number: Boolean = false,
        password: Boolean = false
    ): TextInputLayout {
        val layout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        layout.hint = hint
        layout.editText?.apply {
            setText(text)
            setSingleLine(true)
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return layout
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun selectableBackground(): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    companion object {
        private const val REQUEST_LOCATION = 4001
    }
}
