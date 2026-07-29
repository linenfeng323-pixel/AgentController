package com.ai.agentcontroller

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ai.agentcontroller.databinding.ActivityWebviewBinding
import org.json.JSONObject

/**
 * WebView 承载 AI 网页（豆包 / DeepSeek / ChatGPT …）。
 *
 * 关键能力：
 *  - 复用 Cookie 与 localStorage，登录一次后免登
 *  - 注入 [JsBridge]，前端 AI 回复通过 `AndroidBridge.onAiReply(text)` 回传
 *  - 自动注入监听脚本：MutationObserver 监听 AI 回复 DOM 节点变化，自动抓取并回传
 *  - 用户在底部点“发送指令”输入自然语言，脚本自动填入网页输入框并点击发送
 *  - AI 回复到达后自动解析为 [CommandBatch] 并执行（可关自动执行只看结果）
 *  - 适配常见 AI 站点（多套选择器兜底），未命中时也能用通用文本抓取
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebviewBinding
    private val handler = Handler(Looper.getMainLooper())
    private var lastReply: String = ""
    private var pendingGoal: String = ""
    private var autoExec: Boolean = true
    private var siteUrl: String = ""

    // Js 桥接对象
    inner class JsBridge {
        @JavascriptInterface
        fun onAiReply(text: String) {
            Log.d("JsBridge", "AI 回复: ${text.take(200)}")
            handler.post { handleAiReply(text) }
        }

        @JavascriptInterface
        fun log(msg: String) {
            CommandLogManager.log("JS", msg)
        }

        @JavascriptInterface
        fun onPageReady() {
            CommandLogManager.info("网页就绪: $siteUrl")
        }

        /** 用户在网页聊天框输入了可被本地识别的快捷指令，直接 root 执行 */
        @JavascriptInterface
        fun onDirectCommand(text: String) {
            Log.d("JsBridge", "直执行指令: $text")
            handler.post { handleDirectCommand(text) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        siteUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: siteUrl
        autoExec = intent.getBooleanExtra(EXTRA_AUTO_EXEC, true)
        title = name
        supportActionBar?.hide()

        setupWebView()
        setupBottomBar()

        if (siteUrl.isNotEmpty()) {
            b.webView.loadUrl(siteUrl)
            CommandLogManager.info("加载: $siteUrl")
        }
    }

    private fun setupWebView() {
        val s = b.webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString.replace("; wv", "")
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 复用 Cookie
        val cookie = android.webkit.CookieManager.getInstance()
        cookie.setAcceptCookie(true)
        cookie.setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectObserverScript()
                injectPromptStyle()
                injectInputInterceptor()
            }
        }
        b.webView.webChromeClient = WebChromeClient()
        b.webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
    }

    private fun setupBottomBar() {
        b.btnBack.setOnClickListener { if (b.webView.canGoBack()) b.webView.goBack() else finish() }
        b.btnHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
        b.btnRefresh.setOnClickListener { b.webView.reload() }
        b.btnLog.setOnClickListener { showLogDialog() }
        b.btnSend.setOnClickListener { showCommandDialog() }
    }

    /** 注入 MutationObserver：监听 AI 回复节点变化，自动回传文本。 */
    private fun injectObserverScript() {
        val js = """
        (function(){
          if (window.__agentObsInstalled) { AndroidBridge && AndroidBridge.onPageReady && AndroidBridge.onPageReady(); return; }
          window.__agentObsInstalled = true;
          AndroidBridge && AndroidBridge.log && AndroidBridge.log('观察脚本已注入');

          function extractText(el){
            if(!el) return '';
            // 取该元素下所有可见文本，去掉按钮/复制提示
            var clone = el.cloneNode(true);
            clone.querySelectorAll('button,svg,[aria-hidden="true"]').forEach(function(n){ n.remove(); });
            return (clone.innerText || clone.textContent || '').trim();
          }

          // 候选回复节点选择器（覆盖主流 AI 站点）
          var selectors = [
            '[data-testid*="conversation"]',
            '[class*="message"] [class*="content"]',
            '[class*="prose"]',
            '[class*="markdown"]',
            '[class*="receive"]',
            '[class*="answer"]',
            '[class*="response"]',
            '[class*="chat-content"]',
            'div[class*="bubble"][class*="left"]',
            'article'
          ];

          function lastReplyText(){
            for (var i=0;i<selectors.length;i++){
              var list = document.querySelectorAll(selectors[i]);
              if (list && list.length){
                var last = list[list.length-1];
                var t = extractText(last);
                if (t && t.length>2) return t;
              }
            }
            // 兜底：取 body 最后一段长文本
            var paras = document.querySelectorAll('p,div,span');
            for (var i=paras.length-1;i>=0;i--){
              var t = (paras[i].innerText||'').trim();
              if (t.length>20) return t;
            }
            return '';
          }

          var lastText = '';
          var timer = setInterval(function(){
            var t = lastReplyText();
            if (t && t !== lastText){
              lastText = t;
              try { AndroidBridge && AndroidBridge.onAiReply && AndroidBridge.onAiReply(t); } catch(e){}
            }
          }, 800);

          // 也监听 DOM 变化，立即响应
          var mo = new MutationObserver(function(){
            var t = lastReplyText();
            if (t && t !== lastText){
              lastText = t;
              try { AndroidBridge && AndroidBridge.onAiReply && AndroidBridge.onAiReply(t); } catch(e){}
            }
          });
          mo.observe(document.body, {childList:true, subtree:true, characterData:true});
        })();
        """.trimIndent()
        b.webView.evaluateJavascript(js, null)
    }

    /** 注入一点 CSS，让网页里被我们识别的回复节点有淡色高亮（调试用，可关）。 */
    private fun injectPromptStyle() {
        val css = """
        (function(){
          if (window.__agentStyle) return; window.__agentStyle = true;
          var s = document.createElement('style');
          s.innerHTML = '[class*="prose"],[class*="markdown"],[class*="answer"]{ outline: 1px dashed rgba(91,108,255,0.25) !important; }';
          document.head.appendChild(s);
        })();
        """.trimIndent()
        b.webView.evaluateJavascript(css, null)
    }

    /** 注入输入拦截器：在网页聊天框输入快捷指令时，直接拦截走本地 root 执行，不再发给 AI。 */
    private fun injectInputInterceptor() {
        val js = """
        (function(){
          if (window.__agentInputInterceptor) return;
          window.__agentInputInterceptor = true;
          AndroidBridge && AndroidBridge.log && AndroidBridge.log('输入拦截器已注入');

          // 本地快捷指令正则匹配表（与 Android 端保持一致）
          var quickPatterns = [
            {re:/^打开(.+)$/, action:'open_app'},
            {re:/^启动(.+)$/, action:'open_app'},
            {re:/^(返回|-back)$/, action:'back'},
            {re:/^(回桌面|home|桌面)$/, action:'home'},
            {re:/^(最近任务|多任务|recents)$/, action:'recents'},
            {re:/^(截图|截屏|screenshot)$/, action:'screenshot'},
            {re:/^(等待|sleep|延时)\\s*(\\d+)(秒|s|毫秒|ms)?$/, action:'wait'},
            {re:/^(点击|click)\\s*(.+)$/, action:'click'},
            {re:/^(输入|input)\\s*(.+)$/, action:'input_text'},
            {re:/^(滑动|swipe)\\s*(.+)$/, action:'swipe'},
            {re:/^(停止|杀掉)\\s*(.+)$/, action:'force_stop'},
            {re:/^(长按|longpress)\\s*(.+)$/, action:'long_press'},
            {re:/^(向上滚动|上滑|scroll up)$/, action:'scroll', dir:'up'},
            {re:/^(向下滚动|下滑|scroll down)$/, action:'scroll', dir:'down'}
          ];

          function isQuickCommand(text){
            var t = text.trim();
            for (var i=0;i<quickPatterns.length;i++){
              if (quickPatterns[i].re.test(t)) return true;
            }
            return false;
          }

          function tryIntercept(el){
            if (!el) return;
            // 拦截回车
            el.addEventListener('keydown', function(e){
              var val = (el.value || el.innerText || '').trim();
              if (e.key === 'Enter' && !e.shiftKey && isQuickCommand(val)){
                e.preventDefault();
                e.stopPropagation();
                AndroidBridge && AndroidBridge.onDirectCommand && AndroidBridge.onDirectCommand(val);
                // 清空输入框
                if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') el.value = '';
                else el.innerText = '';
                return false;
              }
            }, true);
          }

          // 监听整个 document 的 click，如果点击的是发送按钮，检查输入框内容
          document.addEventListener('click', function(e){
            var target = e.target;
            var btnText = (target.innerText || target.getAttribute('aria-label') || '').trim();
            var isSendBtn = /^(发送|send|send message|提交|确认)$/i.test(btnText);
            if (!isSendBtn) {
              // 也匹配一些 svg/icon 按钮：父级包含发送文字
              var parent = target.closest('button');
              if (parent) {
                btnText = (parent.innerText || parent.getAttribute('aria-label') || '').trim();
                isSendBtn = /^(发送|send|send message|提交|确认)$/i.test(btnText);
                if (isSendBtn) target = parent;
              }
            }
            if (isSendBtn) {
              // 找附近的输入框
              var form = target.closest('form');
              var ta = form ? form.querySelector('textarea,input[type="text"],div[contenteditable="true"]') : null;
              if (!ta) ta = document.querySelector('textarea:focus,input:focus,[contenteditable="true"]:focus');
              if (!ta) {
                // 兜底：页面主输入框
                var tas = document.querySelectorAll('textarea,div[contenteditable="true"]');
                for (var i=tas.length-1;i>=0;i--){
                  var v = (tas[i].value||tas[i].innerText||'').trim();
                  if (v.length>0){ ta = tas[i]; break; }
                }
              }
              var val = (ta ? (ta.value || ta.innerText || '') : '').trim();
              if (val && isQuickCommand(val)){
                e.preventDefault();
                e.stopPropagation();
                AndroidBridge && AndroidBridge.onDirectCommand && AndroidBridge.onDirectCommand(val);
                if (ta) {
                  if (ta.tagName === 'TEXTAREA' || ta.tagName === 'INPUT') ta.value = '';
                  else ta.innerText = '';
                }
                return false;
              }
            }
          }, true);

          // 也尝试给当前已存在的输入框绑定
          var existing = document.querySelectorAll('textarea,input[type="text"],div[contenteditable="true"]');
          existing.forEach(tryIntercept);

          // 动态新增输入框也绑定
          var mo = new MutationObserver(function(muts){
            muts.forEach(function(m){
              m.addedNodes.forEach(function(n){
                if (n.nodeType === 1){
                  if (n.matches && (n.matches('textarea') || n.matches('input') || n.matches('[contenteditable="true"]'))) tryIntercept(n);
                  if (n.querySelectorAll) {
                    n.querySelectorAll('textarea,input[type="text"],div[contenteditable="true"]').forEach(tryIntercept);
                  }
                }
              });
            });
          });
          mo.observe(document.body, {childList:true, subtree:true});
        })();
        """.trimIndent()
        b.webView.evaluateJavascript(js, null)
    }

    /** 处理本地快捷指令：直接解析并 root 执行，不走 AI。 */
    private fun handleDirectCommand(text: String) {
        CommandLogManager.info("快捷指令: $text")
        val t = text.trim()

        // ======= 阶段 1：用完整自然语言解析器先跑一遍 =======
        val r = NaturalLanguageResolver.resolve(t)

        // GitHub 操作
        r.githubOp?.let { op ->
            handleGithubOp(op)
            return
        }

        // MCP 工具
        if (r.mcpTool != null) {
            Thread {
                val res = McpServerExt.callTool(r.mcpTool, r.mcpArgs ?: org.json.JSONObject())
                CommandLogManager.info("MCP 结果: ${res.toString().take(500)}")
            }.start()
            Toast.makeText(this, "MCP 工具：${r.mcpTool}", Toast.LENGTH_SHORT).show()
            return
        }

        // 计划任务
        r.planText?.let { plan ->
            val state = PlanEngine.parseFromText(plan)
            if (state != null) {
                Toast.makeText(this, "计划已创建：${state.tasks.size} 步", Toast.LENGTH_SHORT).show()
                Thread {
                    PlanEngine.execute(state.id) { st ->
                        CommandLogManager.info("计划进度: ${(st.progress() * 100).toInt()}%")
                    }
                }.start()
                return
            }
        }

        // 直接指令列表
        if (r.commands.isNotEmpty()) {
            Toast.makeText(this, "执行 ${r.commands.size} 条指令", Toast.LENGTH_SHORT).show()
            Thread { HybridExecutor.executeAll(r.commands) }.start()
            return
        }

        // ======= 阶段 2：原有的快捷指令映射兜底 =======
        val cmd: AgentCommand? = when {
            t.matches(Regex("^打开(.+)$")) -> {
                val app = t.replaceFirst("打开", "").trim()
                AgentCommand(action = "open_app", target = app, pkg = app)
            }
            t.matches(Regex("^启动(.+)$")) -> {
                val app = t.replaceFirst("启动", "").trim()
                AgentCommand(action = "open_app", target = app, pkg = app)
            }
            t.matches(Regex("^(返回|-back)$")) -> AgentCommand(action = "back")
            t.matches(Regex("^(回桌面|home|桌面)$", RegexOption.IGNORE_CASE)) -> AgentCommand(action = "home")
            t.matches(Regex("^(最近任务|多任务|recents)$", RegexOption.IGNORE_CASE)) -> AgentCommand(action = "recents")
            t.matches(Regex("^(截图|截屏|screenshot)$", RegexOption.IGNORE_CASE)) -> AgentCommand(action = "screenshot")
            t.matches(Regex("^(等待|sleep|延时)\\s*(\\d+)(秒|s|毫秒|ms)?$", RegexOption.IGNORE_CASE)) -> {
                val ms = Regex("\\d+").find(t)?.value?.toLongOrNull() ?: 1000
                val isSec = t.contains("秒") || t.contains("s")
                AgentCommand(action = "wait", ms = if (isSec) ms * 1000 else ms)
            }
            t.matches(Regex("^(点击|click)\\s*(.+)$", RegexOption.IGNORE_CASE)) -> {
                val target = t.replaceFirst(Regex("^(点击|click)\\s*", RegexOption.IGNORE_CASE), "").trim()
                AgentCommand(action = "click", target = target)
            }
            t.matches(Regex("^(输入|input)\\s*(.+)$", RegexOption.IGNORE_CASE)) -> {
                val txt = t.replaceFirst(Regex("^(输入|input)\\s*", RegexOption.IGNORE_CASE), "").trim()
                AgentCommand(action = "input_text", text = txt)
            }
            t.matches(Regex("^(滑动|swipe)\\s*(.+)$", RegexOption.IGNORE_CASE)) -> {
                val coords = t.replaceFirst(Regex("^(滑动|swipe)\\s*", RegexOption.IGNORE_CASE), "").trim()
                AgentCommand(action = "swipe", target = coords)
            }
            t.matches(Regex("^(停止|杀掉)\\s*(.+)$", RegexOption.IGNORE_CASE)) -> {
                val app = t.replaceFirst(Regex("^(停止|杀掉)\\s*", RegexOption.IGNORE_CASE), "").trim()
                AgentCommand(action = "force_stop", target = app, pkg = app)
            }
            t.matches(Regex("^(长按|longpress)\\s*(.+)$", RegexOption.IGNORE_CASE)) -> {
                val target = t.replaceFirst(Regex("^(长按|longpress)\\s*", RegexOption.IGNORE_CASE), "").trim()
                val parts = target.split(",").map { it.trim().toFloatOrNull() ?: 0f }
                if (parts.size >= 2) AgentCommand(action = "long_press", x = parts[0], y = parts[1])
                else AgentCommand(action = "long_press", target = target)
            }
            t.matches(Regex("^(向上滚动|上滑|scroll up)$", RegexOption.IGNORE_CASE)) -> AgentCommand(action = "scroll", direction = "up")
            t.matches(Regex("^(向下滚动|下滑|scroll down)$", RegexOption.IGNORE_CASE)) -> AgentCommand(action = "scroll", direction = "down")
            else -> null
        }

        if (cmd == null) {
            CommandLogManager.warn("未识别的快捷指令: $t")
            Toast.makeText(this, "未识别指令: $t", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "执行: ${cmd.action}", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = HybridExecutor.execute(cmd)
            CommandLogManager.info("快捷指令结果: ${cmd.action} -> $ok")
        }.start()
    }

    /** 在 AI 聊天框里触发 GitHub 自动编译 / 监控 / 自动修复 */
    private fun handleGithubOp(op: String) {
        val cfg = ConfigManager.get()
        if (cfg.githubToken.isBlank()) {
            Toast.makeText(this, "请先在设置页填入 GitHub Token", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            when (op) {
                "trigger_build" -> {
                    // 空提交触发 CI
                    val r = RootShellExecutor.execBatch(listOf(
                        "cd /storage/emulated/0/XINCODE/AgentController || cd ${filesDir.parent}/AgentController",
                        "git add -A",
                        "git -c user.email=agent@trae.cn -c user.name=TraeAgent commit --allow-empty -m 'trigger build'",
                        "git push https://x-access-token:${shellEscape(cfg.githubToken)}@github.com/${cfg.githubRepo}.git ${cfg.githubBranch}"
                    ))
                    CommandLogManager.info(if (r.ok) "已触发构建" else "触发失败: ${r.err}")
                }
                "watch" -> {
                    val runId = latestRunId(cfg.githubToken, cfg.githubRepo, cfg.githubBranch)
                    if (runId != null) {
                        CommandLogManager.info("监控 Run #$runId")
                        val status = pollRun(cfg.githubToken, cfg.githubRepo, runId)
                        CommandLogManager.info("Run #$runId 结果: $status")
                    }
                }
                "auto_fix" -> {
                    val ok = ContextSentinel.buildAndFixLoop(
                        token = cfg.githubToken,
                        repo = cfg.githubRepo,
                        branch = cfg.githubBranch,
                        commitMsg = "auto-fix: from device"
                    ) { CommandLogManager.info(it) }
                    CommandLogManager.info(if (ok) "✅ 自动修复成功" else "❌ 自动修复失败")
                }
                "push" -> {
                    val r = RootShellExecutor.execBatch(listOf(
                        "cd /storage/emulated/0/XINCODE/AgentController || cd ${filesDir.parent}/AgentController",
                        "git add -A",
                        "git -c user.email=agent@trae.cn -c user.name=TraeAgent commit -m 'device update' --allow-empty",
                        "git push https://x-access-token:${shellEscape(cfg.githubToken)}@github.com/${cfg.githubRepo}.git ${cfg.githubBranch}"
                    ))
                    CommandLogManager.info(if (r.ok) "推送成功" else "推送失败: ${r.err}")
                }
            }
        }.start()
        Toast.makeText(this, "GitHub: $op", Toast.LENGTH_SHORT).show()
    }

    private fun shellEscape(s: String): String = s.replace("'", "'\\''")

    private fun latestRunId(token: String, repo: String, branch: String): Long? =
        ghApiArr(token, "/repos/$repo/actions/runs?branch=$branch&per_page=3")?.let { arr ->
            if (arr.length() > 0) arr.getJSONObject(0).optLong("id") else null
        }

    private fun pollRun(token: String, repo: String, runId: Long): String {
        repeat(120) {
            val o = ghApi(token, "/repos/$repo/actions/runs/$runId")
            val status = o?.optString("status") ?: ""
            if (status == "completed") return o.optString("conclusion", "unknown")
            Thread.sleep(10_000)
        }
        return "timeout"
    }

    private fun ghApi(token: String, path: String): org.json.JSONObject? {
        return runCatching {
            val url = java.net.URL("https://api.github.com$path")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            runCatching { org.json.JSONObject(text) }.getOrNull()
        }.getOrNull()
    }
    private fun ghApiArr(token: String, path: String): org.json.JSONArray? {
        return runCatching {
            val url = java.net.URL("https://api.github.com$path")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Authorization", "token $token")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            org.json.JSONArray(c.inputStream.bufferedReader().readText())
        }.getOrNull()
    }

    /** 处理 AI 回复：解析 + 可选自动执行。 */
    private fun handleAiReply(text: String) {
        if (text == lastReply) return
        lastReply = text
        CommandLogManager.info("AI 回复: ${text.take(200)}")
        // 上下文哨兵
        val st = ContextSentinel.check(text)
        if (st.compressed) {
            CommandLogManager.warn("上下文压缩检测：${if (st.needResendPrompt) "建议重发系统提示" else "已标记"}")
        }
        // 计划
        val plan = PlanEngine.parseFromText(text)
        if (plan != null && plan.tasks.size > 1) {
            CommandLogManager.ok("创建计划: ${plan.tasks.size} 步")
            Thread { PlanEngine.execute(plan.id) { p ->
                CommandLogManager.info("计划进度: ${(p.progress() * 100).toInt()}%")
            } }.start()
            return
        }
        val batch = CommandParser.parse(text)
        if (batch.commands.isEmpty()) {
            CommandLogManager.warn("回复未识别为指令")
            return
        }
        CommandLogManager.info("解析出 ${batch.commands.size} 条指令: ${batch.explain}")
        if (autoExec) {
            Thread {
                val results = HybridExecutor.executeAll(batch.commands)
                if (pendingGoal.isNotBlank()) {
                    RecordManager.saveHistory(pendingGoal, batch, results)
                    ConfigManager.get().also { ConfigManager.addRecentGoal(pendingGoal) }
                    pendingGoal = ""
                }
            }.start()
        }
    }

    private fun showCommandDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_command, null)
        val edit = view.findViewById<android.widget.EditText>(R.id.cmdEdit)
        val chk = view.findViewById<android.widget.CheckBox>(R.id.autoExecCheck)
        chk.isChecked = autoExec
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("发送给 AI") { _, _ ->
                val goal = edit.text.toString().trim()
                autoExec = chk.isChecked
                if (goal.isNotEmpty()) sendGoalToAi(goal)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把自然语言目标填入网页输入框并点发送。 */
    private fun sendGoalToAi(goal: String) {
        pendingGoal = goal
        CommandLogManager.info("发送指令: $goal")
        val wrapped = buildWrappedPrompt(goal)
        val js = """
        (function(){
          var goal = ${jsStringLiteral(wrapped)};
          // 候选输入框选择器
          var ta = document.querySelector('textarea[id*="input"],textarea[placeholder],textarea,contenteditable[contenteditable="true"],div[contenteditable="true"]');
          if (!ta){ AndroidBridge && AndroidBridge.log && AndroidBridge.log('未找到输入框'); return; }
          // 设置值
          if (ta.tagName === 'TEXTAREA' || ta.tagName === 'INPUT'){
            var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value') || Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
            if (setter && setter.set){ setter.set.call(ta, goal); }
            else { ta.value = goal; }
            ta.dispatchEvent(new Event('input',{bubbles:true}));
            ta.dispatchEvent(new Event('change',{bubbles:true}));
          } else {
            ta.focus();
            document.execCommand('insertText', false, goal);
          }
          // 找发送按钮
          setTimeout(function(){
            var btn = null;
            var btns = document.querySelectorAll('button,[role="button"]');
            for (var i=0;i<btns.length;i++){
              var t = (btns[i].innerText||btns[i].getAttribute('aria-label')||'').trim();
              if (/^(发送|send|send message|确认|提交|发送消息)$/i.test(t)){ btn = btns[i]; break; }
            }
            // 兜底：textarea 旁边的 button
            if (!btn && ta.closest('form')){ btn = ta.closest('form').querySelector('button[type="submit"],button'); }
            if (btn){ btn.click(); AndroidBridge && AndroidBridge.log && AndroidBridge.log('已点击发送'); }
            else { // 兜底回车
              var ev = new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true});
              ta.dispatchEvent(ev);
              AndroidBridge && AndroidBridge.log && AndroidBridge.log('已模拟回车');
            }
          }, 300);
        })();
        """.trimIndent()
        b.webView.evaluateJavascript(js, null)
    }

    /** 把用户目标包装成给 AI 的提示，要求输出结构化指令。 */
    private fun buildWrappedPrompt(goal: String): String {
        return """你是一个安卓手机操控助手，请根据用户目标，输出一个 JSON 操作计划。只输出 JSON，不要多余解释。

用户目标：$goal

可用动作：
- open_app：打开 App（package 或 target 写包名或应用名）
- click：点击（target 写界面文字或 "x,y" 坐标）
- tap：点击坐标（x,y）
- long_press：长按坐标
- input_text：输入文本（target 写输入框附近文字，text 写要输入的内容）
- scroll：滚动（direction: up/down/left/right，amount 像素）
- swipe：滑动
- back：返回
- home：回桌面
- recents：最近任务
- wait：等待（ms 毫秒）
- open_url：打开网址（target）
- force_stop：强制停止 App
- screenshot：截图
- observe：观察并报告当前屏幕
- notify：仅提示用户（target 写提示文字）

输出格式：
{
  "explain": "简短说明你打算怎么做",
  "commands": [
    {"action":"open_app","target":"微信"},
    {"action":"wait","ms":800},
    {"action":"click","target":"搜索"}
  ]
}"""
    }

    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 32) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun showLogDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_log, null)
        val logText = view.findViewById<android.widget.TextView>(R.id.logText)
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.logScroll)
        val cb: (String) -> Unit = { text ->
            handler.post {
                logText.text = text
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
        CommandLogManager.observe(cb)
        AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener { CommandLogManager.stopObserve(cb) }
            .show()
        view.findViewById<android.widget.Button>(R.id.btnClearLog).setOnClickListener {
            CommandLogManager.clear()
        }
    }

    override fun onBackPressed() {
        if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        b.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_AUTO_EXEC = "extra_auto_exec"

        fun launch(ctx: android.content.Context, name: String, url: String, autoExec: Boolean) {
            val i = Intent(ctx, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_AUTO_EXEC, autoExec)
            }
            ctx.startActivity(i)
        }
    }
}
