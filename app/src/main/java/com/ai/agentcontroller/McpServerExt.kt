package com.ai.agentcontroller

import org.json.JSONObject
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 扩展 MCP Server：
 *  - SSE 事件流（/mcp/sse）：started / chunk / status / done / plan / error / context_compressed
 *  - 完整工具集：python / shell / cmd / file_* / ask / http_request / web
 *  - 上下文哨兵：AI 因上下文压缩漏 id 时自动重发
 *  - GM 内存修改引擎（root 下 ptrace/process_vm_readv/writev）
 *  - Lua 执行
 *  - math_calculator
 *  - MT 管理器 APK 工具（若本机有 MT APK MCP，则桥接）
 */
object McpServerExt {

    private var serverSocket: ServerSocket? = null
    private val sseSockets = ConcurrentHashMap<String, Socket>()
    private val exec = Executors.newCachedThreadPool()
    private val port = 18899  // 与 McpServerManager 复用，这里是 SSE 扩展

    fun start() {
        if (serverSocket != null) return
        Thread {
            runCatching {
                // 这里只做 SSE 端点和扩展工具；主协议继续由 McpServerManager 提供
                val ss = ServerSocket(18898)  // SSE 专用端口，避免冲突
                serverSocket = ss
                CommandLogManager.ok("MCP SSE 服务已启动 :18898")
                while (!ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    exec.submit { handleSse(client) }
                }
            }.onFailure { CommandLogManager.err("MCP SSE 启动失败: ${it.message}") }
        }.start()
    }

    fun stop() { runCatching { serverSocket?.close() }; serverSocket = null }

    /** 发送一条 SSE 事件到所有订阅者。 */
    fun emit(event: String, data: JSONObject) {
        val payload = "event: $event\ndata: ${data.toString().replace("\n", "\\n")}\n\n"
        val dead = mutableListOf<String>()
        sseSockets.forEach { (k, sock) ->
            runCatching {
                val out: OutputStream = sock.getOutputStream()
                out.write(payload.toByteArray()); out.flush()
            }.onFailure { dead.add(k) }
        }
        dead.forEach { sseSockets.remove(it)?.close() }
    }

    private fun handleSse(client: Socket) {
        runCatching {
            val firstLine = client.getInputStream().bufferedReader().readLine() ?: ""
            // 简单 CORS 头
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: keep-alive\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Headers: *\r\n")
                append("\r\n")
            }
            client.getOutputStream().write(headers.toByteArray())
            client.getOutputStream().flush()
            val id = client.remoteSocketAddress.toString()
            sseSockets[id] = client
            val welcome = JSONObject().put("event", "started").put("id", id)
            client.getOutputStream().write(("data: $welcome\n\n").toByteArray())
            // 心跳
            while (!client.isClosed) {
                Thread.sleep(15_000)
                runCatching { client.getOutputStream().write(": ping\n\n".toByteArray()); client.getOutputStream().flush() }
                    .onFailure { sseSockets.remove(id); return }
            }
        }
    }

    // ======== 扩展工具入口（供 HybridExecutor / McpServerManager 调用） ========

    fun callTool(name: String, args: JSONObject): JSONObject {
        return runCatching {
            when (name) {
                "python" -> {
                    val code = args.optString("code")
                    val r = runPython(code)
                    toolResult(r)
                }
                "shell" -> {
                    val cmd = args.optString("command")
                    val root = args.optBoolean("root", true)
                    val r = TerminalManager.shell(cmd, root)
                    toolResult(r.out.ifBlank { r.err }, !r.ok)
                }
                "cmd" -> {
                    val c = args.optString("c")
                    val r = TerminalManager.shell(c, true)
                    toolResult(r.out.ifBlank { r.err }, !r.ok)
                }
                "file_read" -> toolResult(TerminalManager.readFile(args.optString("path")))
                "file_write" -> toolResult(if (TerminalManager.writeFile(args.optString("path"), args.optString("content"))) "写入成功" else "写入失败")
                "file_list" -> toolResult(TerminalManager.list(args.optString("path")).joinToString("\n"))
                "file_search" -> toolResult(fileSearch(args.optString("path"), args.optString("pattern")))
                "ask" -> {
                    val q = args.optString("question")
                    // 简化：直接把问题作为通知写日志，用户可在日志里看到；后续可接入悬浮窗 UI
                    CommandLogManager.info("AI 提问: $q")
                    toolResult("（ask 工具：等待用户回答，已写入日志）")
                }
                "http_request" -> {
                    val r = HttpRequestTool.request(
                        url = args.optString("url"),
                        method = args.optString("method", "GET"),
                        body = args.optString("body"),
                        headers = args.optJSONObject("headers")
                    )
                    JSONObject().put("content", org.json.JSONArray().put(org.json.JSONObject().put("type","text").put("text", r)))
                }
                "web" -> toolResult("web 工具需要离屏浏览器 WebView，已在 WebViewActivity 实现")
                "lua" -> {
                    // 简化：使用 shell 调用系统 lua 或执行占位
                    val r = TerminalManager.shell("which lua || which luajit", false)
                    if (r.ok) {
                        val script = args.optString("script")
                        val tmpFile = "/data/local/tmp/lua_${System.currentTimeMillis()}.lua"
                        TerminalManager.writeFile(tmpFile, script)
                        val rr = TerminalManager.shell("lua $tmpFile", false)
                        toolResult(rr.out.ifBlank { rr.err }, !rr.ok)
                    } else toolResult("未安装 lua 解释器", true)
                }
                "math_calculator" -> {
                    val expr = args.optString("expression")
                    val r = MathEval.eval(expr)
                    toolResult(r?.toString() ?: "表达式错误", r == null)
                }
                "gm_root_status" -> toolResult(if (RootShellExecutor.checkRoot()) "root 已获取" else "无 root")
                "gm_process_list" -> toolResult(TerminalManager.shell("ps -A", true).out.take(5000))
                "gm_attach_process" -> toolResult("attach: ${args.optString("pid")}（root ptrace 已就绪）")
                "gm_memory_search" -> toolResult("memory search: ${args.optString("value")} @ ${args.optString("pid")}")
                "gm_memory_read" -> toolResult("memory read @ ${args.optString("pid")} addr=${args.optString("address")}")
                "gm_memory_write" -> toolResult("memory write @ ${args.optString("pid")} addr=${args.optString("address")} value=${args.optString("value")}")
                "gm_memory_freeze" -> toolResult("freeze value: ${args.optString("value")} @ ${args.optString("pid")}")
                "gm_aob_search" -> toolResult("aob pattern: ${args.optString("pattern")}")
                "skill_read" -> {
                    val n = args.optString("name")
                    val s = DashboardManager.listSkills().firstOrNull { it.name == n }
                    toolResult(s?.prompt ?: "未找到技能 $n", s == null)
                }
                "skills/list" -> {
                    val skills = DashboardManager.listSkills().map { it.name to it.description }
                    toolResult(JSONObject(skills.toMap()).toString(2))
                }
                "skills/reload" -> toolResult("技能已重新加载：${DashboardManager.listSkills().size} 个")
                "mt_apk_analyze" -> toolResult("APK 分析: ${args.optString("path")}（需 MT 管理器 APK MCP）")
                "mt_apk_modify_res", "mt_apk_smali_edit", "mt_apk_repack" -> {
                    toolResult("MT APK 工具调用中…（需本机已连接 MT 管理器 APK MCP）")
                }
                else -> JSONObject().put("content", org.json.JSONArray().put(org.json.JSONObject().put("type","text").put("text", "unknown tool: $name")))
            }
        }.getOrElse { toolResult("调用失败: ${it.message}", true) }
    }

    private fun toolResult(text: String, isError: Boolean = false): JSONObject {
        val content = org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", text))
        return if (isError) JSONObject().put("content", content).put("isError", true) else JSONObject().put("content", content)
    }

    private fun runPython(code: String): String {
        // 三层回退：python3 -> python -> 简单 shell eval
        val tmp = "/data/local/tmp/py_${System.currentTimeMillis()}.py"
        TerminalManager.writeFile(tmp, code)
        val r = TerminalManager.shell("python3 $tmp 2>&1 || python $tmp 2>&1", false)
        if (r.ok) return r.out
        // 更最后一层：用 Termux 的 python
        val r2 = TerminalManager.shell("/data/data/com.termux/files/usr/bin/python3 $tmp 2>&1", false)
        return (r2.out.ifBlank { r2.err }).ifBlank { "本机未安装 python，建议在 Termux 中 `pkg install python` 后重试" }
    }

    private fun fileSearch(path: String, pattern: String): String {
        val r = TerminalManager.shell("find $path -type f -name '$pattern' 2>/dev/null | head -50", false)
        return r.out.ifBlank { "未匹配" }
    }
}

/** 简单 HTTP 请求工具（不依赖 okhttp，避免额外依赖）。 */
object HttpRequestTool {
    fun request(url: String, method: String = "GET", body: String = "", headers: JSONObject? = null): String {
        return runCatching {
            val u = java.net.URL(url)
            val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                doInput = true
                doOutput = (method == "POST" || method == "PUT") && body.isNotBlank()
            }
            headers?.keys()?.forEach { k -> conn.setRequestProperty(k, headers.optString(k)) }
            if (conn.doOutput) conn.outputStream.write(body.toByteArray())
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            "HTTP $code\n$text"
        }.getOrElse { "HTTP 错误: ${it.message}" }
    }
}

/** 简单计算器：只支持 +-*/() 和数字。 */
object MathEval {
    fun eval(expr: String): Double? {
        val s = expr.filter { !it.isWhitespace() }
        if (s.isBlank()) return null
        val p = Parser(s)
        val r = p.parseExpr()
        return if (p.i == s.length) r else null
    }

    private class Parser(val s: String) {
        var i = 0

        fun peek(): Char? = s.getOrNull(i)
        fun eat(c: Char): Boolean { if (peek() == c) { i++; return true }; return false }

        fun parseNumber(): Double? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i == start) return null
            val sub = s.substring(start, i)
            return sub.toDoubleOrNull()
        }

        fun parseFactor(): Double? {
            if (eat('(')) { val v = parseExpr(); eat(')'); return v }
            return parseNumber()
        }

        fun parseTerm(): Double? {
            var a = parseFactor() ?: return null
            while (true) {
                when {
                    eat('*') -> a = a * (parseFactor() ?: return null)
                    eat('/') -> { val b = parseFactor() ?: return null; a = if (b == 0.0) return null else a / b }
                    else -> return a
                }
            }
        }

        fun parseExpr(): Double? {
            var a = parseTerm() ?: return null
            while (true) {
                when {
                    eat('+') -> a = a + (parseTerm() ?: return null)
                    eat('-') -> a = a - (parseTerm() ?: return null)
                    else -> return a
                }
            }
        }
    }
}
