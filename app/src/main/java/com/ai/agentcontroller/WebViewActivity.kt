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

    /** 处理 AI 回复：解析 + 可选自动执行。 */
    private fun handleAiReply(text: String) {
        if (text == lastReply) return
        lastReply = text
        CommandLogManager.info("AI 回复: ${text.take(200)}")
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
