package com.ai.agentcontroller

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.gridlayout.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ai.agentcontroller.AiSiteResolver.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页：Mac 风格侧边 Dock + 多页面内容。
 *
 * 页面：首页（搜索 + AI 列表）/ 录制 / 诊断 / 设置。
 * 底部状态条显示无障碍状态，可一键跳转开启。
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    // 首页视图
    private lateinit var searchEdit: EditText
    private lateinit var hotSitesGrid: RecyclerView
    private lateinit var recentList: RecyclerView
    private lateinit var accessibilityBar: View
    private lateinit var statusDot: View
    private lateinit var accessibilityStatus: TextView

    private val recentSites = mutableListOf<Site>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install()
        ConfigManager.load()
        setContentView(R.layout.activity_main)

        setupDock()
        showHomePage()
        refreshAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        if (recentSites.isEmpty()) refreshRecentFromConfig()
    }

    // ===== Dock 导航 =====

    private fun setupDock() {
        val navIds = listOf(R.id.navHome, R.id.navAi, R.id.navRecord, R.id.navDiagnose, R.id.navSettings, R.id.navLog)
        navIds.forEach { id -> findViewById<ImageButton>(id).setOnClickListener { onNavClick(id) } }
        selectNav(R.id.navHome)
    }

    private fun onNavClick(id: Int) {
        when (id) {
            R.id.navHome, R.id.navAi -> { showHomePage(); selectNav(R.id.navHome) }
            R.id.navRecord -> { showRecordPage(); selectNav(R.id.navRecord) }
            R.id.navDiagnose -> { showDiagnosePage(); selectNav(R.id.navDiagnose) }
            R.id.navSettings -> { showSettingsPage(); selectNav(R.id.navSettings) }
            R.id.navLog -> showLogDialog()
        }
    }

    private fun selectNav(id: Int) {
        val all = listOf(R.id.navHome, R.id.navAi, R.id.navRecord, R.id.navDiagnose, R.id.navSettings)
        all.forEach { findViewById<ImageButton>(it).isSelected = (it == id) }
    }

    private fun swapContent(layoutRes: Int): View {
        val container = findViewById<android.widget.FrameLayout>(R.id.contentContainer)
        container.removeAllViews()
        val v = LayoutInflater.from(this).inflate(layoutRes, container, false)
        container.addView(v)
        return v
    }

    // ===== 首页 =====

    private fun showHomePage() {
        val v = swapContent(R.layout.page_home)
        searchEdit = v.findViewById(R.id.searchEdit)
        hotSitesGrid = v.findViewById(R.id.hotSitesGrid)
        recentList = v.findViewById(R.id.recentList)
        accessibilityBar = v.findViewById(R.id.accessibilityBar)
        statusDot = v.findViewById(R.id.statusDot)
        accessibilityStatus = v.findViewById(R.id.accessibilityStatus)

        v.findViewById<View>(R.id.goButton).setOnClickListener { onGo() }
        searchEdit.setOnEditorActionListener { _, _, _ -> onGo(); true }
        v.findViewById<View>(R.id.openAccessibilityBtn).setOnClickListener {
            AccessibilityServiceHelper.openAccessibilitySettings()
        }

        // 热门 AI：GridLayoutManager 3 列
        hotSitesGrid.layoutManager = GridLayoutManager(this, 3)
        hotSitesGrid.adapter = SiteAdapter(AiSiteResolver.hotSites) { site -> openSite(site) }

        recentList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        refreshRecentFromConfig()
    }

    private fun refreshRecentFromConfig() {
        val cfg = ConfigManager.get()
        recentSites.clear()
        if (cfg.lastSiteUrl.isNotEmpty()) {
            recentSites.add(Site(cfg.lastSiteName.ifBlank { "上次" }, "📌", cfg.lastSiteUrl, emptyList()))
        }
        if (recentList?.adapter == null) return
        (recentList.adapter as SiteAdapter).update(recentSites)
    }

    private fun onGo() {
        val input = searchEdit.text.toString().trim()
        if (input.isEmpty()) { toast("请输入 AI 名称或网址"); return }
        val (name, url) = AiSiteResolver.resolve(input)
        if (url.isEmpty()) { toast("无法识别：$input"); return }
        val site = Site(name, "🔗", url, emptyList())
        openSite(site)
    }

    private fun openSite(site: Site) {
        val cfg = ConfigManager.get()
        cfg.lastSiteName = site.name
        cfg.lastSiteUrl = site.url
        ConfigManager.save(cfg)
        WebViewActivity.launch(this, site.name, site.url, cfg.autoExecute)
    }

    // ===== 录制页 =====

    private fun showRecordPage() {
        val v = swapContent(R.layout.page_record)
        val nameEdit = v.findViewById<EditText>(R.id.recordName)
        val list = v.findViewById<RecyclerView>(R.id.recordsList)
        val historyText = v.findViewById<TextView>(R.id.historyText)
        list.layoutManager = LinearLayoutManager(this)
        historyText.text = RecordManager.historySummary()

        v.findViewById<View>(R.id.btnStartRecord).setOnClickListener {
            RecordManager.startRecord(nameEdit.text.toString())
            toast("已开始录制")
        }
        v.findViewById<View>(R.id.btnStopRecord).setOnClickListener {
            RecordManager.stopRecord()
            refreshRecords(list)
        }
        refreshRecords(list)
    }

    private fun refreshRecords(list: RecyclerView) {
        val names = RecordManager.listRecords()
        list.adapter = RecordAdapter(names) { name ->
            RecordManager.replay(name); toast("回放: $name")
        }
    }

    // ===== 诊断页 =====

    private fun showDiagnosePage() {
        val v = swapContent(R.layout.page_diagnose)
        val summary = v.findViewById<TextView>(R.id.diagSummary)
        val detail = v.findViewById<TextView>(R.id.diagDetail)
        v.findViewById<View>(R.id.btnRunDiag).setOnClickListener {
            summary.text = "诊断中…"
            GlobalScope.launch {
                val r = withContext(Dispatchers.IO) { DeviceDiagnostics.run() }
                handler.post {
                    summary.text = r.summary
                    detail.text = r.details
                }
            }
        }
        v.findViewById<View>(R.id.btnDumpTomb).setOnClickListener {
            GlobalScope.launch {
                val t = withContext(Dispatchers.IO) { CrashReporter.dumpRecentTombstones() }
                handler.post { detail.text = t }
            }
        }
    }

    // ===== 设置页 =====

    private fun showSettingsPage() {
        val v = swapContent(R.layout.page_settings)
        val cfg = ConfigManager.get()
        v.findViewById<android.widget.CheckBox>(R.id.cbAutoExec).isChecked = cfg.autoExecute
        v.findViewById<android.widget.CheckBox>(R.id.cbUseRoot).isChecked = cfg.useRoot
        v.findViewById<android.widget.CheckBox>(R.id.cbUseAcc).isChecked = cfg.useAccessibility
        v.findViewById<android.widget.CheckBox>(R.id.cbShot).isChecked = cfg.screenshotOnError
        v.findViewById<android.widget.CheckBox>(R.id.cbBridge).isChecked = cfg.pcBridgeEnabled
        v.findViewById<EditText>(R.id.etBridgeUrl).setText(cfg.pcBridgeUrl)
        v.findViewById<EditText>(R.id.etMaxSteps).setText(cfg.maxSteps.toString())

        v.findViewById<View>(R.id.btnSaveCfg).setOnClickListener {
            val c = ConfigManager.get()
            c.autoExecute = v.findViewById<android.widget.CheckBox>(R.id.cbAutoExec).isChecked
            c.useRoot = v.findViewById<android.widget.CheckBox>(R.id.cbUseRoot).isChecked
            c.useAccessibility = v.findViewById<android.widget.CheckBox>(R.id.cbUseAcc).isChecked
            c.screenshotOnError = v.findViewById<android.widget.CheckBox>(R.id.cbShot).isChecked
            c.pcBridgeEnabled = v.findViewById<android.widget.CheckBox>(R.id.cbBridge).isChecked
            c.pcBridgeUrl = v.findViewById<EditText>(R.id.etBridgeUrl).text.toString()
            c.maxSteps = v.findViewById<EditText>(R.id.etMaxSteps).text.toString().toIntOrNull() ?: 30
            ConfigManager.save(c)
            toast("已保存")
        }
        v.findViewById<View>(R.id.btnRestoreCfg).setOnClickListener {
            ConfigManager.restoreOriginal()
            toast("已恢复原配置")
            showSettingsPage()
        }
        v.findViewById<View>(R.id.btnOpenOverlay).setOnClickListener {
            AccessibilityServiceHelper.openOverlaySettings()
        }
    }

    // ===== 状态 =====

    private fun refreshAccessibilityStatus() {
        if (!::statusDot.isInitialized) return
        val ok = AccessibilityServiceHelper.isEnabled()
        statusDot.setBackgroundResource(if (ok) R.drawable.bg_status_dot_on else R.drawable.bg_status_dot_off)
        accessibilityStatus.text = if (ok) getString(R.string.accessibility_on) else getString(R.string.accessibility_off)
    }

    // ===== 日志弹窗 =====

    private fun showLogDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_log, null)
        val logText = view.findViewById<TextView>(R.id.logText)
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.logScroll)
        val cb: (String) -> Unit = { text ->
            handler.post {
                logText.text = text
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        CommandLogManager.observe(cb)
        AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener { CommandLogManager.stopObserve(cb) }
            .show()
        view.findViewById<View>(R.id.btnClearLog).setOnClickListener { CommandLogManager.clear() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ===== Adapter =====

    inner class SiteAdapter(private var items: List<Site>, private val onClick: (Site) -> Unit) :
        RecyclerView.Adapter<SiteAdapter.VH>() {
        fun update(newItems: List<Site>) { items = newItems; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_site, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(s: Site) {
                itemView.findViewById<TextView>(R.id.siteEmoji).text = s.emoji
                itemView.findViewById<TextView>(R.id.siteName).text = s.name
                itemView.findViewById<TextView>(R.id.siteUrl).text = s.url
                itemView.findViewById<TextView>(R.id.siteBadge).text = "进入 ›"
                itemView.setOnClickListener { onClick(s) }
            }
        }
    }

    inner class RecordAdapter(private val items: List<String>, private val onReplay: (String) -> Unit) :
        RecyclerView.Adapter<RecordAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(name: String) {
                itemView.findViewById<TextView>(R.id.recordName).text = name
                itemView.findViewById<View>(R.id.btnReplay).setOnClickListener { onReplay(name) }
            }
        }
    }
}
