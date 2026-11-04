package com.halo.expressassistant.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.halo.expressassistant.TrackingNotifier
import com.halo.expressassistant.api.KuaiDi100
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.api.XiaomiSync
import com.halo.expressassistant.data.ExpressDetail
import com.halo.expressassistant.data.ExpressItem
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.data.displayProgress
import com.halo.expressassistant.data.sectionKeyOf
import com.halo.expressassistant.databinding.ActivityMainBinding
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ExpressAdapter
    private var appliedTheme: String = ""


    private lateinit var identityPanel: IdentityPanel
    private var selectedTab = com.halo.expressassistant.R.id.nav_express
    private var searchQuery = ""
    private var recognitionJob: kotlinx.coroutines.Job? = null
    private var pendingMailNo = ""
    private var pendingCompany: KuaiDi100.Company? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        appliedTheme = Themes.current(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        Paper.apply(this, binding.root, binding.toolbar)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
        adapter = ExpressAdapter(onClick = { openDetail(it) }, onLongClick = { showPackageSheet(it) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> binding.list.canScrollVertically(-1) }
        binding.swipeRefresh.setOnRefreshListener {
            if (Store.hasAnyAccount(this)) syncAll()
            else { binding.swipeRefresh.isRefreshing = false; Toast.makeText(this, "请先登录账号", Toast.LENGTH_SHORT).show() }
        }
        identityPanel = IdentityPanel(this)
        binding.identityPanel.addView(identityPanel.view, ViewGroup.LayoutParams(-1, -1))
        binding.bottomNavigation.setOnItemSelectedListener { showTab(it.itemId); true }
        binding.btnDone.setOnClickListener { startActivity(Intent(this, DonePanelActivity::class.java)) }
        binding.btnCalendar.setOnClickListener { showCalendarSheet() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty(); reload(); recogniseInput(searchQuery)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.searchInput.setOnEditorActionListener { _, action, event ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP) { submitSearch(); true } else false
        }
        binding.btnAddPackage.setOnClickListener { addFromSearch() }
        selectedTab = savedInstanceState?.getInt("selected_tab") ?: if (intent.getBooleanExtra("identity_code", false)) com.halo.expressassistant.R.id.nav_identity else com.halo.expressassistant.R.id.nav_express
        binding.bottomNavigation.selectedItemId = selectedTab
        showTab(selectedTab); reload(); maybeAutoSync()
        if (intent.getBooleanExtra("sync_now", false)) binding.root.post { handleSyncNow() }
        if (intent.getBooleanExtra("add", false)) binding.root.post { binding.searchInput.requestFocus() }
        binding.root.post { handleNavigationIntent(intent) }
        binding.root.post { reportFullyDrawn() }
        lifecycleScope.launch {
            val result = UpdateChecker.check(this@MainActivity)
            if (result.hasUpdate && !result.downloadUrl.isNullOrBlank()) MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("发现新版本 v${result.latestTag}").setMessage("当前版本 v${UpdateChecker.versionName(this@MainActivity)}")
                .setPositiveButton("下载") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(result.downloadUrl))) }
                .setNegativeButton("稍后", null).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(tab: Int) {
        selectedTab = tab
        val express = tab == com.halo.expressassistant.R.id.nav_express
        binding.expressPanel.visibility = if (express) View.VISIBLE else View.GONE
        binding.identityPanel.visibility = if (express) View.GONE else View.VISIBLE
        binding.toolbarTitle.text = if (express) "快递" else "身份码"
        binding.btnCalendar.visibility = if (express) View.VISIBLE else View.GONE
        binding.btnDone.visibility = if (express) View.VISIBLE else View.GONE
        if (express) identityPanel.hide() else {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
            binding.searchInput.clearFocus(); identityPanel.show()
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("identity_code", false)) {
            binding.bottomNavigation.selectedItemId = com.halo.expressassistant.R.id.nav_identity
        }
        if (intent.getBooleanExtra("sync_now", false)) {
            binding.root.post { handleSyncNow() }
        }
        binding.root.post { handleNavigationIntent(intent) }
    }

    /** Maintenance/widget navigation through the existing launcher. */
    private fun handleNavigationIntent(intent: Intent) {
        val query = intent.getStringExtra("query")
        val mail = intent.getStringExtra("mailNo")
        if (query != null || mail != null) binding.bottomNavigation.selectedItemId = com.halo.expressassistant.R.id.nav_express
        if (query != null) binding.searchInput.setText(query)
        if (mail != null) Store.items(this).firstOrNull { it.mailNo == mail }?.let(::openDetail)
    }

    /** 小组件刷新入口：四源同步完成后自动退回桌面，保持"刷新"语义 */
    private fun handleSyncNow() {
        val fromWidget = intent.getBooleanExtra("sync_now", false)
        syncAll {
            if (fromWidget && !isFinishing) {
                binding.root.postDelayed({ moveTaskToBack(true) }, 400)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != Themes.current(this)) { recreate(); return }
        reload()
        if (selectedTab == com.halo.expressassistant.R.id.nav_identity) identityPanel.show()
        com.halo.expressassistant.widget.ExpressWidgetProvider.updateAll(this)
        PddTraceBackfill.backfill(this, lifecycleScope)
    }

    override fun onPause() {
        identityPanel.hide()
        super.onPause()
    }

    /** 首页显示在途；搜索包含历史，工具栏可打开完成/异常列表。 */
    fun reload() {
        val all = Store.items(this)
        val goods = Store.jdGoods(this)
        val q = searchQuery.lowercase()
        val rows = if (q.isBlank()) all else all.filter { item ->
            listOf(item.mailNo, item.companyName, item.latestText, item.pickupCode, goods[item.mailNo]?.name.orEmpty()).any { it.lowercase().contains(q) }
        }
        adapter.setSearchMode(q.isNotBlank()); adapter.submit(rows)
        val empty = if (q.isNotBlank()) rows.isEmpty() else rows.none { isTransportItem(it) }
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyState.text = if (q.isNotBlank()) "没有找到快递" else "没有在途快递"
        if (selectedTab == com.halo.expressassistant.R.id.nav_identity) identityPanel.reloadFromStore()
    }

    /** 在途判定（与适配器 bucket 一致）：0/1/2/5 归在途，3 完成、4/108-111 异常 */
    private fun isTransportItem(item: ExpressItem): Boolean {
        when (item.partitionOverride) {
            "delivering", "shipped", "notshipped", "unknown" -> return true
            "done", "abnormal" -> return false
        }
        return when {
            item.isCompleted() -> false
            item.state == 4 -> false
            item.stateNum in setOf(106, 107, 108, 109, 110, 111) -> false
            else -> true
        }
    }

    private fun arrivalDateOf(item: ExpressItem): Pair<Int, Int>? {
        val eta = item.eta
        if (eta.isBlank()) return null
        val m = Regex("(\\d{1,2})月(\\d{1,2})日").find(eta) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun showCalendarSheet() {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        val items = Store.items(this).filter { isTransportItem(it) }
        content.addView(
            TextView(this).apply {
                text = "在途包裹日历（完成/异常请在二级菜单查看）"
                textSize = 12f
                setTextColor(onSurfaceVariant())
                setPadding(0, 0, 0, dp(6))
            }
        )
        val arrivalsByDate = HashMap<Pair<Int, Int>, MutableList<ExpressItem>>()
        for (item in items) {
            arrivalDateOf(item)?.let { d ->
                arrivalsByDate.getOrPut(d) { mutableListOf() }.add(item)
            }
        }
        val now = Calendar.getInstance()
        var year = now.get(Calendar.YEAR)
        var month = now.get(Calendar.MONTH)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val title = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val prev = ImageButton(this).apply {
            setImageResource(com.halo.expressassistant.R.drawable.ic_chevron_left)
            setBackgroundResource(selectableBackground())
            contentDescription = "上个月"
        }
        val next = ImageButton(this).apply {
            setImageResource(com.halo.expressassistant.R.drawable.ic_chevron_right)
            setBackgroundResource(selectableBackground())
            contentDescription = "下个月"
        }
        nav.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        nav.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(nav)

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(grid)

        fun render() {
            title.text = "${year}年${month + 1}月"
            grid.removeAllViews()
            val header = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            for (name in listOf("日", "一", "二", "三", "四", "五", "六")) {
                header.addView(
                    TextView(this@MainActivity).apply {
                        text = name
                        gravity = Gravity.CENTER
                        textSize = 12f
                        setTextColor(onSurfaceVariant())
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            grid.addView(header)
            val first = Calendar.getInstance().apply { set(year, month, 1) }
            val leading = first.get(Calendar.DAY_OF_WEEK) - 1
            val daysInMonth = Calendar.getInstance().apply { set(year, month + 1, 0) }.get(Calendar.DAY_OF_MONTH)
            var day = 1
            val totalCells = ((leading + daysInMonth + 6) / 7) * 7
            for (idx in 0 until totalCells) {
                if (idx % 7 == 0) {
                    grid.addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL })
                }
                val row = grid.getChildAt(grid.childCount - 1) as LinearLayout
                if (idx < leading || day > daysInMonth) {
                    row.addView(TextView(this@MainActivity), LinearLayout.LayoutParams(0, dp(46), 1f))
                } else {
                    val d = day++
                    val arrivals = arrivalsByDate[(month + 1) to d] ?: emptyList()
                    val isToday = year == now.get(Calendar.YEAR) &&
                        month == now.get(Calendar.MONTH) &&
                        d == now.get(Calendar.DAY_OF_MONTH)
                    val cell = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        setOnClickListener {
                            val c = Calendar.getInstance().apply { set(year, month, d) }
                            showArrivalsSheet(c, arrivals)
                        }
                    }
                    cell.addView(
                        TextView(this@MainActivity).apply {
                            text = d.toString()
                            textSize = 15f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                            if (isToday) {
                                setTextColor(
                                    MaterialColors.getColor(
                                        this@MainActivity,
                                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                                        0
                                    )
                                )
                                setBackgroundResource(com.halo.expressassistant.R.drawable.bg_icon_circle)
                            } else {
                                setTextColor(
                                    MaterialColors.getColor(this@MainActivity, android.R.attr.textColorPrimary, 0)
                                )
                            }
                        }
                    )
                    cell.addView(
                        View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(2) }
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(
                                    if (arrivals.isNotEmpty()) {
                                        MaterialColors.getColor(this@MainActivity, android.R.attr.colorPrimary, 0)
                                    } else {
                                        android.graphics.Color.TRANSPARENT
                                    }
                                )
                            }
                        }
                    )
                    row.addView(cell, LinearLayout.LayoutParams(0, dp(46), 1f))
                }
            }
        }

        prev.setOnClickListener {
            month--
            if (month < 0) {
                month = 11
                year--
            }
            render()
        }
        next.setOnClickListener {
            month++
            if (month > 11) {
                month = 0
                year++
            }
            render()
        }
        render()

        val scroll = ScrollView(this)
        scroll.addView(content)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun showArrivalsSheet(c: Calendar, arrivals: List<ExpressItem>) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(
            TextView(this).apply {
                text = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 到达"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        content.addView(
            TextView(this).apply {
                text = if (arrivals.isEmpty()) "当天暂无快递到达" else "共 ${arrivals.size} 件"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(10))
            }
        )
        for (item in arrivals) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundResource(selectableBackground())
                setOnClickListener { openDetail(item) }
            }
            row.addView(
                TextView(this).apply {
                    text = item.companyName
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )
            row.addView(
                TextView(this).apply {
                    text = "${item.mailNo} · 预计 ${item.eta}"
                    textSize = 13f
                    setTextColor(onSurfaceVariant())
                }
            )
            content.addView(row)
        }
        sheet.setContentView(content)
        sheet.show()
    }

    private fun syncAll(onDone: (() -> Unit)? = null) {
        Store.setLastAutoSync(this, System.currentTimeMillis())
        // 调试用：intent extra "skip_channels"（逗号分隔 xiaomi/jd/taobao）模拟未登录组合
        val skip = intent.getStringExtra("skip_channels")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        CoroutineScope(Dispatchers.Main).launch {
            var spinnerStopped = false
            fun stopSpinnerIfNeeded() {
                if (!spinnerStopped) {
                    spinnerStopped = true
                    binding.swipeRefresh.isRefreshing = false
                }
            }
            // 硬上限：转圈最多 20s，之后无论如何先停圈（同步继续后台跑完）——
            // 兜底一切慢/坏场景（PDD 登录验证挂起、渠道全超时），「转圈不停」不再可能
            val spinnerCap = launch {
                kotlinx.coroutines.delay(20_000)
                stopSpinnerIfNeeded()
            }
            try {
                // 流式：拼多多每增量一批新单 → 立刻入库+刷新（总件数实时上涨）；
                // 首批到达即停止下拉转圈（在途/完成第一页4件/异常第一页4件已可显示）
                // 已移除单号快照：流式增量入库同样跳过，防「移除后下次刷新复活」
                val hiddenNos = Store.xiaomiHidden(this@MainActivity).mapTo(HashSet()) { it.mailNo }
                val report = SyncEngine.sync(this@MainActivity, skip, onPddProgress = { batch, goods ->
                    if (batch.isNotEmpty()) {
                        // 在途优先：批次中「在途」立即入库+显示；完成/异常等本轮抓取 finish
                        // （syncInternal 收尾统一保存），实现「在途优先、完成/异常后台跑」
                        val transportBatch = batch.filter { isTransportItem(it) }
                        if (transportBatch.isNotEmpty()) {
                            stopSpinnerIfNeeded()
                            // 商品图流式入库（保留已优化 shortName）——完成/异常的图在收尾统一落库
                            if (goods.isNotEmpty()) {
                                val merged = Store.jdGoods(this@MainActivity).toMutableMap()
                                goods.forEach { (k, v) ->
                                    val old = merged[k]
                                    merged[k] = if (old != null && old.shortName.isNotBlank()) v.copy(shortName = old.shortName) else v
                                }
                                Store.saveJdGoods(this@MainActivity, merged)
                            }
                            val current = Store.items(this@MainActivity).toMutableList()
                            val known = current.map { it.mailNo }.toSet()
                            val add = transportBatch.filter { it.mailNo !in known && it.source == "pdd" && it.mailNo !in hiddenNos }
                            if (add.isNotEmpty()) {
                                current.addAll(add)
                                Store.saveItems(this@MainActivity, current)
                                reload()
                            }
                        } else {
                            // 完成/异常批：打断语义——本轮在途未收尾则暂不显示（收尾后统一入库并刷新）
                            stopSpinnerIfNeeded()
                        }
                    }
                }, onFastChannelsDone = { stopSpinnerIfNeeded() })
                // 在途列表此刻已全量落库 → 立即停圈；详情刷新/轨迹补抓/短名优化继续后台跑，
                // 不再拖着转圈等它们（反馈：转圈要到「所有包裹包括已完成」刷完才停）
                spinnerCap.cancel()
                stopSpinnerIfNeeded()
                // 下拉刷新只报告「在途包裹」刷新状态；完成/异常后台静默加载，不提示。
                // 轨迹后台补抓，列表同步完成即可停止刷新动画。
                launch { runCatching { refreshAllDetails() } }
                val transportCount = Store.items(this@MainActivity).count { isTransportItem(it) }
                val pddError = report.statuses.firstOrNull { it.channel == "pdd" }?.error
                android.widget.Toast.makeText(
                    this@MainActivity,
                    pddError?.let { "在途包裹：$transportCount 件；拼多多：$it" }
                        ?: "在途包裹已更新：$transportCount 件",
                    if (pddError != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
                ).show()
                reload()
                // 同步完成后强制立刻补抓（回填已抓缓存到卡片 + 补抓过期单），防轨迹外显被同步覆盖丢失
                PddTraceBackfill.backfill(this@MainActivity, this, force = true)
            } catch (e: Throwable) {
                android.widget.Toast.makeText(this@MainActivity, e.message ?: "同步失败", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                spinnerCap.cancel()
                binding.swipeRefresh.isRefreshing = false
                onDone?.invoke()
            }
        }
    }

    private val precomputeMutex = Mutex()
    private val saveMutex = Mutex()
    @Volatile private var precomputeRunning = false
    @Volatile private var lastPrecomputeAt = 0L

    /** 同步后并行更新在途件的轨迹。 */
    private suspend fun refreshAllDetails(): Int {
        if (!precomputeMutex.tryLock()) return 0
        try {
            precomputeRunning = true
            lastPrecomputeAt = System.currentTimeMillis()
            val all = Store.items(this@MainActivity).toMutableList()
            val targets = all.withIndex().filter { (_, it) ->
                it.source == "xiaomi" &&
                    it.state != 3 && it.stateNum !in 108..111
            }
            if (targets.isEmpty()) return 0
            val sem = Semaphore(4)
            var updated = 0
            coroutineScope {
                for ((i, item) in targets) {
                    launch {
                        sem.withPermit {
                            var current = item
                            var trajectory = current.latestText
                            try {
                                val account = Store.accountForItem(this@MainActivity, current)
                                val cred = account?.let { Store.parseXiaomiCred(it.payload) }
                                    ?: Store.xiaomiCred(this@MainActivity)
                                val detail = com.halo.expressassistant.api.XiaomiDetail.fetchWith(
                                    this@MainActivity, current, cred
                                )
                                trajectory = detail.data.joinToString("\n") { "${it.time} ${it.context}" }
                                if (applyDetail(current, detail)) updated++
                                current = Store.items(this@MainActivity).firstOrNull { it.mailNo == item.mailNo } ?: current
                            } catch (e: Throwable) {
                                if (Store.kd100Fallback(this@MainActivity)) {
                                    try {
                                        val detail = KuaiDi100.fetchDetail(this@MainActivity, current)
                                        trajectory = detail.data.joinToString("\n") { "${it.time} ${it.context}" }
                                        if (applyDetail(current, detail)) updated++
                                        current = Store.items(this@MainActivity).firstOrNull { it.mailNo == item.mailNo } ?: current
                                    } catch (ignored: Throwable) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return updated
        } finally {
            precomputeRunning = false
            precomputeMutex.unlock()
        }
    }

    /** 详情落库（latestText/state/eta + 跟踪通知），有更新返回 true。saveMutex 串行化防并行丢更新。 */
    private suspend fun applyDetail(old: ExpressItem, detail: com.halo.expressassistant.data.ExpressDetail): Boolean {
        val last = detail.data.firstOrNull { it.context.isNotBlank() } ?: detail.data.firstOrNull() ?: return false
        val newText = last.context
        val newTime = last.formattedTime.ifBlank { last.time }
        var notifiedText = old.notifiedText
        var notifiedTime = old.notifiedTime
        if (old.tracked) {
            if (notifiedText.isBlank() && notifiedTime.isBlank()) {
                notifiedText = newText
                notifiedTime = newTime
            } else if (newTime.isNotEmpty() && newTime > notifiedTime) {
                TrackingNotifier.notify(this@MainActivity, old.copy(latestText = newText, latestTime = newTime))
                notifiedText = newText
                notifiedTime = newTime
            }
        }
        saveMutex.lock()
        try {
            val items = Store.items(this@MainActivity).toMutableList()
            val idx = items.indexOfFirst { it.mailNo == old.mailNo }
            if (idx < 0) return false
            items[idx] = items[idx].copy(
                latestText = newText,
                latestTime = newTime,
                notifiedText = notifiedText,
                notifiedTime = notifiedTime,
                state = detail.state,
                eta = com.halo.expressassistant.api.EtaParser.extract(
                    detail.data.joinToString(" ") { it.context }
                ).ifBlank { items[idx].eta }
            )
            Store.saveItems(this@MainActivity, items)
            return true
        } finally {
            saveMutex.unlock()
        }
    }

    private fun maybeAutoSync() {
        if (!Store.hasAnyAccount(this)) return
        val now = System.currentTimeMillis()
        if (now - Store.lastAutoSync(this) < 60_000) return
        Store.setLastAutoSync(this, now)
        // 与 syncAll 相同的 skip_channels 处理：冷启动自动化调试时也能跳过指定渠道
        val skip = intent.getStringExtra("skip_channels")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                SyncEngine.sync(this@MainActivity, skip)
                refreshAllDetails()
                reload()
            } catch (e: Throwable) {
                // 静默失败，用户手动同步时会看到提示
            }
        }
    }

    private fun openDetail(item: ExpressItem) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("item", Store.json.encodeToString(item)))
    }





    private fun recogniseInput(raw: String) {
        recognitionJob?.cancel()
        val mail = com.halo.expressassistant.api.ManualPackages.normalise(raw)
        pendingMailNo = ""; pendingCompany = null
        val exists = Store.items(this).any { it.mailNo.equals(mail, true) }
        if (!com.halo.expressassistant.api.ManualPackages.isWaybill(mail) || exists) {
            binding.pendingPackage.visibility = View.GONE
            return
        }
        binding.pendingPackage.visibility = View.VISIBLE
        binding.pendingLabel.text = "$mail · 识别中"
        recognitionJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(500)
            val company = detectCompany(mail)
            if (com.halo.expressassistant.api.ManualPackages.normalise(searchQuery) != mail) return@launch
            pendingMailNo = mail; pendingCompany = company
            binding.pendingLabel.text = "${company?.name?.ifBlank { "快递" } ?: "快递公司待识别"} · $mail"
        }
    }

    private suspend fun detectCompany(mail: String): KuaiDi100.Company? {
        val matched = withContext(Dispatchers.IO) {
            if (Store.xiaomiToken(this@MainActivity).isBlank()) null else runCatching {
                XiaomiApi.matchCompany(this@MainActivity, Store.xiaomiToken(this@MainActivity), Store.xiaomiCUser(this@MainActivity),
                    mailNo = mail, accountId = Store.xiaomiAccountId(this@MainActivity), oaid = Store.xiaomiOaid(this@MainActivity), vaid = Store.xiaomiVaid(this@MainActivity))
            }.getOrNull()
        }
        return matched?.let { KuaiDi100.Company(it.first, it.second) } ?: KuaiDi100.detectCompany(mail)
    }

    private fun submitSearch() {
        val mail = com.halo.expressassistant.api.ManualPackages.normalise(searchQuery)
        val existing = Store.items(this).firstOrNull { it.mailNo.equals(mail, true) }
        if (existing != null) openDetail(existing)
        else if (com.halo.expressassistant.api.ManualPackages.isWaybill(mail)) addFromSearch()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun addFromSearch() {
        val mail = com.halo.expressassistant.api.ManualPackages.normalise(searchQuery)
        if (!com.halo.expressassistant.api.ManualPackages.isWaybill(mail)) return
        if (Store.items(this).any { it.mailNo.equals(mail, true) }) { reload(); return }
        recognitionJob?.cancel(); binding.btnAddPackage.isEnabled = false
        lifecycleScope.launch {
            try {
                val company = if (pendingMailNo == mail && pendingCompany != null) pendingCompany else detectCompany(mail)
                if (Store.items(this@MainActivity).none { it.mailNo.equals(mail, true) }) {
                    val item = ExpressItem(id = mail, mailNo = mail, companyCode = company?.comCode.orEmpty(),
                        companyName = company?.name?.ifBlank { "快递" } ?: "快递", source = "manual", state = -1,
                        stateNum = 0, stateName = "待查询", originalName = company?.name.orEmpty())
                    Store.saveItems(this@MainActivity, Store.items(this@MainActivity) + item)
                    Store.restoreHidden(this@MainActivity, mail)
                    binding.searchInput.setText(""); reload()
                    Toast.makeText(this@MainActivity, "已添加", Toast.LENGTH_SHORT).show()
                    withContext(Dispatchers.IO) { runCatching { com.halo.expressassistant.api.ManualPackages.refresh(this@MainActivity, item) } }
                    reload()
                }
            } finally { binding.btnAddPackage.isEnabled = true }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun primary(): Int =
        MaterialColors.getColor(this, android.R.attr.colorPrimary, 0)

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun selectableBackground(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    private fun showPackageSheet(item: ExpressItem) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(com.halo.expressassistant.R.drawable.bg_status_chip)
            load(item.iconUrl) {
                crossfade(true)
                placeholder(com.halo.expressassistant.R.drawable.ic_package)
                error(com.halo.expressassistant.R.drawable.ic_package)
                transformations(CircleCropTransformation())
            }
            if (item.iconUrl.isBlank()) setImageResource(com.halo.expressassistant.R.drawable.ic_package)
        }
        header.addView(icon)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(this).apply {
                text = item.companyName
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        texts.addView(
            TextView(this).apply {
                text = item.mailNo
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header)

        val progress = displayProgress(item)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(6))
        }
        statusRow.addView(
            TextView(this).apply {
                text = item.stateLabel()
                textSize = 15f
                setTextColor(primary())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val progressText = TextView(this).apply {
            text = if (item.stateNum in 108..111) "已终止"
            else if (progress >= 0) "运输进度：$progress%"
            else "待更新"
            textSize = 14f
            setTextColor(primary())
        }
        statusRow.addView(progressText)
        content.addView(statusRow)
        if (item.stateNum !in 108..111 && progress >= 0) {
            val progressBar = LinearProgressIndicator(this).apply {
                max = 100
                isIndeterminate = progress < 0
                if (progress >= 0) setProgressCompat(progress, true)
                trackThickness = dp(6)
                setIndicatorColor(primary())
                setTrackColor(MaterialColors.getColor(this@MainActivity, android.R.attr.colorControlNormal, 0))
            }
            content.addView(progressBar)
            val eta = item.eta
            if (eta.isNotBlank()) {
                val etaView = TextView(this).apply {
                    text = "预计送达：$eta"
                    textSize = 13f
                    setTextColor(primary())
                    setPadding(0, dp(6), 0, 0)
                }
                content.addView(etaView)
            }
        }

        val trackRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(6))
        }
        val trackTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trackTexts.addView(
            TextView(this).apply {
                text = "跟踪快递"
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        trackTexts.addView(
            TextView(this).apply {
                text = "有新动态时通知我"
                textSize = 13f
                setTextColor(onSurfaceVariant())
            }
        )
        trackRow.addView(trackTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        trackRow.addView(
            SwitchMaterial(this).apply {
                isChecked = item.tracked
                setOnCheckedChangeListener { _, checked ->
                    updateTracked(item, checked)
                }
            }
        )
        content.addView(trackRow)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        buttons.addView(
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "修改名称"
                setIconResource(com.halo.expressassistant.R.drawable.ic_edit)
                setOnClickListener {
                    sheet.dismiss()
                    showRenameSheet(item)
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )
        buttons.addView(
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "移除快递"
                setIconResource(com.halo.expressassistant.R.drawable.ic_delete)
                setTextColor(Color.rgb(179, 38, 30))
                setOnClickListener {
                    sheet.dismiss()
                    removeItem(item)
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(buttons)

        // 指定地址：可单独选择已有地址（未指定 = 使用全局当前地址）
        val addrLabelText = Store.addressLabelForItem(this, item) ?: ""
        content.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (item.addressId.isNotBlank()) "指定地址：$addrLabelText" else "指定地址：使用全局默认"
                setIconResource(com.halo.expressassistant.R.drawable.ic_location)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    sheet.dismiss()
                    showAddressPicker(item)
                }
            }
        )

        sheet.setContentView(content)
        sheet.show()
    }

    /** 快递单件「指定地址」选择器 */
    private fun showAddressPicker(item: ExpressItem) {
        val addrs = Store.addresses(this)
        if (addrs.isEmpty()) {
            Toast.makeText(this, "还没有地址，请先到 设置 → 我的地址 添加", Toast.LENGTH_LONG).show()
            return
        }
        val (sheet, container) = Sheets.create(this, "指定收件地址")
        container.addView(
            TextView(this).apply {
                text = "为 ${item.companyName} ${item.mailNo} 选择收件地址；不指定则用全局当前地址。"
                textSize = 13f
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this@MainActivity, android.R.attr.textColorSecondary, Color.GRAY
                ))
                setPadding(0, 0, 0, dp(12))
            }
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(box)

        fun refresh() {
            box.removeAllViews()
            // 使用全局默认（清除指定）
            val useDefault = MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "使用全局默认（${Store.homeAddress(this@MainActivity).take(16) ?: ""}）"
                textSize = 13f
                gravity = android.view.Gravity.START
                setOnClickListener {
                    setItemAddress(item, "")
                    sheet.dismiss()
                }
            }
            box.addView(useDefault, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            val activeId = Store.activeAddressId(this@MainActivity)
            for (a in addrs) {
                val isActive = a.id == item.addressId || (item.addressId.isBlank() && (a.id == activeId || (activeId.isBlank() && a == addrs.first())))
                box.addView(
                    MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "${a.label}　${a.address.take(18)}" + if (isActive) "　✓" else ""
                        textSize = 13f
                        gravity = android.view.Gravity.START
                        setOnClickListener {
                            setItemAddress(item, a.id)
                            sheet.dismiss()
                        }
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                )
            }
        }
        refresh()
        sheet.show()
    }

    /** 写回单件指定地址。 */
    private fun setItemAddress(item: ExpressItem, addressId: String) {
        val items = Store.items(this).map {
            if (it.mailNo == item.mailNo) it.copy(addressId = addressId)
            else it
        }
        Store.saveItems(this, items)
        reload()
        Toast.makeText(this, if (addressId.isBlank()) "已切换为全局默认地址" else "已指定地址", Toast.LENGTH_SHORT).show()
    }

    /** 顶部搜索：按 单号/快递公司/商品名/状态/绑定账号 全文过滤（本地即时） */




    private fun updateTracked(item: ExpressItem, tracked: Boolean) {
        if (tracked) Store.ensurePollingDefault(this)
        val items = Store.items(this).map {
            if (it.mailNo == item.mailNo) {
                if (tracked) {
                    it.copy(tracked = true, notifiedText = it.latestText, notifiedTime = it.latestTime)
                } else {
                    it.copy(tracked = false)
                }
            } else {
                it
            }
        }
        Store.saveItems(this, items)
        android.widget.Toast.makeText(
            this,
            if (tracked) "已开启跟踪，有新动态会通知你" else "已关闭跟踪",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showRenameSheet(item: ExpressItem) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        content.addView(
            TextView(this).apply {
                text = "修改名称"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(14))
            }
        )
        val labelLayout = layoutInflater.inflate(com.halo.expressassistant.R.layout.view_input_outlined, null) as TextInputLayout
        labelLayout.hint = "自定义名称"
        val input = labelLayout.editText!!.apply {
            setText(item.companyName)
            setSingleLine(true)
        }
        content.addView(labelLayout)
        content.addView(
            TextView(this).apply {
                text = "重置为原名 ›"
                textSize = 13f
                setTextColor(primary())
                setPadding(0, dp(10), 0, 0)
                isClickable = true
                setOnClickListener {
                    val original = item.originalName.ifBlank { item.companyName }
                    input.setText(original)
                }
            }
        )
        content.addView(
            MaterialButton(this).apply {
                text = "保存"
                setIconResource(com.halo.expressassistant.R.drawable.ic_checklist)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
                setOnClickListener {
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        val items = Store.items(this@MainActivity).map {
                            if (it.mailNo == item.mailNo) it.copy(companyName = name) else it
                        }
                        Store.saveItems(this@MainActivity, items)
                        reload()
                        sheet.dismiss()
                    }
                }
            }
        )
        sheet.setContentView(content)
        sheet.show()
    }

    private fun removeItem(item: ExpressItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除快递")
            .setMessage("将 ${item.companyName}（${item.mailNo}）移除？可在设置里的“删除快递”中恢复。")
            .setPositiveButton("移除") { _, _ ->
                val all = Store.items(this)
                Store.addHidden(this, item)
                Store.saveItems(this, all.filterNot { it.mailNo == item.mailNo })
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            syncAll()
        }
    }











}
