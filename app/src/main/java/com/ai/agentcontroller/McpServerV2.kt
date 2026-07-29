package com.ai.agentcontroller

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 严格对齐 agent-toolbox v2.4.36 的 MCP 服务端：
 *  - JSON-RPC 2.0 over HTTP（端口 8080）
 *  - 完整 CORS 支持（预检 + 响应头）
 *  - HTTP chunked SSE（/sse）started/chunk/status/done/plan/error/context_compressed
 *  - test_client.html 本地前端（/）：marked + KaTeX 无 CDN
 *  - 线程池管理 HTTP 连接；HandlerThread 专线写入 SSE
 *  - 正则预编译池；提示词模板缓存
 *  - 协议接口：initialize / tools/list / tools/call / skills/list / skills/reload / notifications/initialized
 *  - 40+ 内置工具：python/shell/sh/cmd/file_* / ask / http_request / web / math_calculator / lua / gm_* / skill_read / skills.list / skills.reload / mt_apk_*
 */
object McpServerV2 {

    private val ctx: Context get() = App.instance

    const val PORT = 8080
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val httpPool = Executors.newCachedThreadPool()
    private val sseHandlerThread = HandlerThread("McpSseWriter").also { it.start() }
    private val sseHandler: Handler = Handler(sseHandlerThread.looper)
    private val sseClients = ConcurrentHashMap<String, Socket>()

    /** 正则预编译池（避免每次重复编译开销） */
    private object Rx {
        val planTasks = Regex("""\{[\s\S]*?"tasks"[\s\S]*?\}""")
        val jsonObj = Regex("""\{[\s\S]*\}""")
        val numberedStep = Regex("""^\d+[\.、)]\s*(.+)$""", RegexOption.MULTILINE)
        val numberOnly = Regex("""^(\d+)[\.、)]\s*(.+)$""")
        val cmdFormat = Regex("""\{\s*"explain"\s*:\s*"([^"]*?)".*?"commands"\s*:\s*\[""")
        val pkgFuzzy = Regex("""[a-zA-Z][a-zA-Z0-9_.]*""")
    }

    /** 提示词模板缓存 */
    private val promptCache = ConcurrentHashMap<String, String>()
    fun getPrompt(name: String, build: () -> String): String = promptCache.getOrPut(name, build)

    /** 红队工具定义（18 个核心工具） */
    private val toolRegistry: List<ToolDef> = buildList {
        // 红队 - APK 逆向
        add(ToolDef("apk_decompile", "反编译 APK：解压+提取manifest+dex+签名", mapOf("path" to "string")))
        add(ToolDef("apk_manifest", "分析 AndroidManifest：导出组件+危险权限", mapOf("path" to "string")))
        add(ToolDef("apk_strings", "提取 APK 中的 URL/IP/密钥/Token", mapOf("path" to "string")))
        // 红队 - 网络侦察
        add(ToolDef("net_scan", "端口扫描", mapOf("target" to "string", "ports" to "string")))
        add(ToolDef("net_hosts", "局域网主机发现", mapOf("subnet" to "string")))
        add(ToolDef("net_wifi", "WiFi 信息收集", mapOf()))
        add(ToolDef("net_dns", "DNS 解析", mapOf("domain" to "string")))
        add(ToolDef("net_capture", "抓包（tcpdump）", mapOf("interface" to "string", "duration" to "integer", "filter" to "string")))
        // 红队 - 内存分析
        add(ToolDef("mem_procs", "枚举进程", mapOf("filter" to "string")))
        add(ToolDef("mem_search", "内存搜索（类似GG）", mapOf("pid" to "integer", "pattern" to "string")))
        add(ToolDef("mem_dump", "内存转储+敏感字符串提取", mapOf("pid" to "integer", "max_mb" to "integer")))
        // 红队 - 二进制分析
        add(ToolDef("bin_elf", "ELF 二进制分析", mapOf("path" to "string")))
        add(ToolDef("bin_dex", "DEX 文件分析", mapOf("path" to "string")))
        // 红队 - 漏洞检测
        add(ToolDef("vuln_privesc", "权限提升检测", mapOf()))
        add(ToolDef("vuln_audit", "安全配置审计", mapOf()))
        // 红队 - Shell & 文件
        add(ToolDef("shell", "执行 root shell 命令", mapOf("command" to "string", "root" to "boolean")))
        add(ToolDef("file_read", "读取文件（任意路径）", mapOf("path" to "string")))
        add(ToolDef("file_search", "搜索文件", mapOf("path" to "string", "pattern" to "string")))
        // AI 对话
        add(ToolDef("chat", "大白话输入，自动识别意图并执行红队操作", mapOf("input" to "string")))
    }

    data class ToolDef(val name: String, val desc: String, val props: Map<String, String> = emptyMap())

    fun port(): Int = PORT

    fun isRunning(): Boolean = serverSocket != null && serverSocket?.isClosed == false

    fun start(): Boolean {
        if (serverSocket != null) return true
        serverThread = Thread {
            runCatching {
                val ss = ServerSocket()
                ss.bind(InetSocketAddress("0.0.0.0", PORT))
                serverSocket = ss
                CommandLogManager.ok("MCP v2 服务已启动 :$PORT  " +
                        "http://${getLocalIp()}:$PORT/")
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    httpPool.submit { handleHttpClient(s) }
                }
            }.onFailure { CommandLogManager.err("MCP v2 启动失败: ${it.message}") }
        }.also { it.start() }
        return true
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverThread?.interrupt()
        serverSocket = null
    }

    private fun getLocalIp(): String {
        return runCatching {
            val ifaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (nf in ifaces) {
                for (a in java.util.Collections.list(nf.inetAddresses)) {
                    if (!a.isLoopbackAddress && a is InetAddress && a.hostAddress.indexOf(':') < 0)
                        return@runCatching a.hostAddress
                }
            }
            "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    /** SSE 广播 */
    fun emitSse(event: String, data: JSONObject) {
        val payload = "event: $event\r\ndata: ${data.toString().replace("\n", "\\n")}\r\n\r\n"
        sseHandler.post {
            val dead = mutableListOf<String>()
            sseClients.forEach { (k, sock) ->
                runCatching {
                    val out: OutputStream = sock.getOutputStream()
                    out.write(payload.toByteArray()); out.flush()
                }.onFailure { dead.add(k) }
            }
            dead.forEach { sseClients.remove(it)?.close() }
        }
    }

    // ===== HTTP 协议处理 =====

    private data class HttpRequest(
        val method: String, val path: String, val headers: Map<String, String>, val body: String
    )

    private fun handleHttpClient(sock: Socket) {
        runCatching {
            sock.soTimeout = 60_000
            val `in`: InputStream = sock.getInputStream()
            val out: OutputStream = sock.getOutputStream()
            val reader: BufferedReader = `in`.bufferedReader()
            var line = reader.readLine()
            if (line == null) { sock.close(); return }
            val parts = line.split(" ", limit = 3)
            if (parts.size < 2) { sock.close(); return }
            val method = parts[0]; val path = parts[1].split("?")[0]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val hl = reader.readLine()
                if (hl.isNullOrBlank()) break
                val idx = hl.indexOf(':')
                if (idx > 0) headers[hl.substring(0, idx).trim().lowercase()] = hl.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) {
                val buf = CharArray(len)
                var read = 0; while (read < len) {
                    val r = reader.read(buf, read, len - read); if (r <= 0) break; read += r
                }
                String(buf)
            } else ""
            val req = HttpRequest(method, path, headers, body)

            // CORS 预检
            if (req.method == "OPTIONS") {
                writeBytes(out, corsPreflight())
                sock.close(); return
            }

            when {
                path == "/" -> serveAsset(out, "test_client.html")
                path == "/mcp" -> handleRpc(req, out)
                path == "/sse" -> handleSseHandshake(sock, out)
                path.startsWith("/assets/") -> serveAsset(out, path.removePrefix("/assets/"))
                path == "/health" -> writeJson(out, JSONObject().put("status", "ok").put("version", "2.4.36"))
                else -> writeBytes(out, "HTTP/1.1 404 Not Found\r\n$corsHeaders\r\nContent-Length: 0\r\n\r\n")
            }
            // SSE 不立即关闭
            if (path != "/sse") {
                runCatching { out.flush() }; sock.close()
            }
        }.onFailure { runCatching { sock.close() } }
    }

    private val corsHeaders: String =
        "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type,Authorization,Accept\r\nAccess-Control-Max-Age: 86400"

    private fun corsPreflight() = "HTTP/1.1 204 No Content\r\n$corsHeaders\r\nContent-Length: 0\r\n\r\n"

    private fun writeBytes(out: OutputStream, s: String) = out.write(s.toByteArray())

    private fun writeJson(out: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        val head = "HTTP/1.1 200 OK\r\n$corsHeaders\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()
        out.write(head); out.write(bytes)
    }

    private fun serveAsset(out: OutputStream, name: String) {
        val bytes = runCatching { ctx.assets.open("web/$name").readBytes() }.getOrNull()
        if (bytes == null) { writeBytes(out, "HTTP/1.1 404 Not Found\r\n$corsHeaders\r\nContent-Length: 0\r\n\r\n"); return }
        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".js") -> "application/javascript; charset=utf-8"
            name.endsWith(".css") -> "text/css; charset=utf-8"
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
        val head = "HTTP/1.1 200 OK\r\n$corsHeaders\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()
        out.write(head); out.write(bytes)
    }

    private fun handleSseHandshake(sock: Socket, out: OutputStream) {
        val head = ("HTTP/1.1 200 OK\r\n$corsHeaders\r\nContent-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache, no-transform\r\nConnection: keep-alive\r\n" +
                "Transfer-Encoding: chunked\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray()
        out.write(head); out.flush()
        val id = "${sock.port}-${System.currentTimeMillis()}"
        sseClients[id] = sock
        // 初始 started 事件
        runCatching {
            val d = "event: started\r\ndata: {\"id\":\"$id\"}\r\n\r\n".toByteArray()
            out.write(d); out.flush()
            // 心跳用 chunked : keep-alive（15s）
            val t = Thread {
                while (!sock.isClosed) {
                    Thread.sleep(15_000)
                    runCatching {
                        val o = sock.getOutputStream()
                        o.write(": ping\r\n\r\n".toByteArray()); o.flush()
                    }.onFailure { sseClients.remove(id); return@Thread }
                }
            }
            t.isDaemon = true; t.start()
        }
    }

    // ===== JSON-RPC 2.0 =====

    private fun handleRpc(req: HttpRequest, out: OutputStream) {
        val resp = runCatching {
            val batch = if (req.body.trimStart().startsWith('[')) JSONArray(req.body) else null
            if (batch != null) {
                val arr = JSONArray()
                for (i in 0 until batch.length()) {
                    processOne(batch.getJSONObject(i))?.let { arr.put(it) }
                }
                "[$arr]"
            } else {
                processOne(JSONObject(req.body))?.toString() ?: ""
            }
        }.getOrElse {
            JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", JSONObject.NULL)
                put("error", JSONObject().put("code", -32603).put("message", it.message ?: "internal"))
            }.toString()
        }
        val bytes = resp.toByteArray()
        val head = "HTTP/1.1 200 OK\r\n$corsHeaders\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()
        out.write(head); out.write(bytes)
    }

    private fun processOne(r: JSONObject): JSONObject? {
        val id = r.opt("id")
        val method = r.optString("method")
        val params = r.optJSONObject("params") ?: JSONObject()
        // 通知：无 id 不回包
        val isNotify = id == null || id == JSONObject.NULL && !r.has("id")

        val result = runCatching {
            when (method) {
                "initialize" -> JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", JSONObject().put("name", "AgentToolbox").put("version", "2.4.36"))
                    put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)).put("skills", true))
                }
                "notifications/initialized" -> { /* handshake ok */ JSONObject() }
                "tools/list" -> {
                    val arr = JSONArray()
                    toolRegistry.forEach { t ->
                        val props = JSONObject()
                        t.props.forEach { (k, v) -> props.put(k, JSONObject().put("type", v)) }
                        arr.put(JSONObject().apply {
                            put("name", t.name); put("description", t.desc)
                            put("inputSchema", JSONObject().put("type","object").put("properties", props))
                        })
                    }
                    JSONObject().put("tools", arr)
                }
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    // 红队工具路由
                    if (name.startsWith("apk_") || name.startsWith("net_") || name.startsWith("mem_") ||
                        name.startsWith("bin_") || name.startsWith("vuln_") || name == "shell" ||
                        name == "file_read" || name == "file_search") {
                        RedTeamEngine.callTool(name, args).toMcp()
                    }
                    // AI 对话路由
                    else if (name == "chat") {
                        val result = RedTeamEngine.smartRoute(args.optString("input"))
                        result.toMcp()
                    }
                    // 兼容旧工具
                    else {
                        McpServerExt.callTool(name, args).apply {
                            if (optBoolean("isError", false)) put("isError", true)
                        }
                    }
                }
                "skills/list" -> {
                    val arr = JSONArray()
                    DashboardManager.listSkills().forEach { s ->
                        arr.put(JSONObject().put("name", s.name).put("description", s.description))
                    }
                    JSONObject().put("skills", arr)
                }
                "skills/reload" -> {
                    val loaded = reloadSkillDirectory()
                    JSONObject().put("loaded", loaded)
                }
                "plan/run" -> {
                    val tasksText = params.toString()
                    val state = PlanEngine.parseFromText(tasksText)
                    if (state != null) {
                        Thread { PlanEngine.execute(state.id) { st -> emitSse("plan", st.toJson()) } }.start()
                        JSONObject().put("planId", state.id).put("tasks", state.tasks.size)
                    } else JSONObject().put("error", "无法解析计划")
                }
                else -> JSONObject().put("error", JSONObject().put("code", -32601).put("message", "Method not found: $method"))
            }
        }.getOrElse {
            JSONObject().put("error", JSONObject().put("code", -32603).put("message", it.message ?: "exec error"))
        }

        if (isNotify) return null
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            if (result.has("error") && !result.has("content")) {
                val err = result.opt("error")
                if (err is JSONObject) put("error", err) else put("error", JSONObject().put("message", err.toString()))
            } else {
                put("result", result)
            }
        }
    }

    // ===== AI 对话处理 =====

    private fun handleAiDialogue(name: String, args: JSONObject): JSONObject {
        val result = when (name) {
            "ai_dialogue_start" -> AiDialogueManager.startUserRequest(args.optString("input"))
            "ai_commander_reply" -> AiDialogueManager.onCommanderReply(args.optString("reply"))
            "ai_executor_reply" -> AiDialogueManager.onExecutorReply(args.optString("reply"))
            "ai_dialogue_history" -> JSONObject().put("dialogue", AiDialogueManager.getDialogueText())
                .put("history", org.json.JSONArray(AiDialogueManager.getHistory().map { it.toJson() }))
            else -> JSONObject().put("error", "未知 AI 对话方法: $name")
        }
        val content = org.json.JSONArray().put(JSONObject().put("type", "text").put("text", result.toString(2)))
        return JSONObject().put("content", content)
    }

    // ===== Skill 热加载 =====

    private val skillDir: File get() = File("${ConfigManager.get().workspaceDir}/skills")

    /** 扫描 skills 目录，把 .md / .txt 技能文件注册到 DashboardManager */
    fun reloadSkillDirectory(): Int {
        val dir = skillDir
        dir.mkdirs()
        val files = dir.listFiles { f -> f.isFile && (f.extension.equals("md", true) || f.extension.equals("txt", true)) } ?: return 0
        var count = 0
        files.forEach { f ->
            runCatching {
                val text = f.readText()
                val name = f.nameWithoutExtension
                val (desc, prompt) = parseSkillMarkdown(text)
                // 同名覆盖
                val list = DashboardManager.listSkills().toMutableList()
                list.removeAll { it.name == name }
                list.add(DashboardManager.SkillTemplate(name, prompt, desc ?: name))
                DashboardManager.saveSkills(list)
                count++
            }
        }
        return count
    }

    /** 解析 .md skill 元数据：
     *  ---
     *  name: xxx
     *  description: xxx
     *  ---
     *  # Prompt
     *  ...内容...
     */
    private fun parseSkillMarkdown(text: String): Pair<String?, String> {
        val fm = Regex("""^---\s*\n([\s\S]*?)\n---\s*\n?([\s\S]*)$""").find(text)
        if (fm == null) return null to text
        val head = fm.groupValues[1]
        val body = fm.groupValues[2].ifBlank { text }
        var desc: String? = null
        head.lineSequence().forEach { l ->
            val parts = l.split(':', limit = 2)
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim()
                if (k.equals("description", true) || k.equals("desc", true)) desc = v
            }
        }
        return desc to body
    }

    // ===== Git 三层回退封装（对外入口） =====
    // 层 1：内嵌静态 git（app/src/main/res/raw/git_arm64 二进制）
    // 层 2：系统 PATH git
    // 层 3：Python dulwich 纯 Python 实现兜底
    fun git(args: List<String>, cwd: File? = null): TerminalManager.ShellResult {
        val argStr = args.joinToString(" ")
        // 层 1：内嵌静态 git
        embeddedGitPath()?.let { g ->
            val r = TerminalManager.shell("$g $argStr", true, cwd?.absolutePath)
            if (r.ok) return r
        }
        // 层 2：系统 git
        val r2 = TerminalManager.shell("git $argStr 2>&1 || /system/bin/git $argStr 2>&1 || /data/local/tmp/git $argStr 2>&1", true, cwd?.absolutePath)
        if (r2.ok || (r2.out.isNotBlank() && !r2.out.contains("not found"))) return r2
        // 层 3：dulwich 纯 Python 兜底
        val dulwichCmd = buildString {
            append("python3 -c \"")
            append("import sys; from dulwich.porcelain import * as d; ")
            append("args=sys.argv[1:]; ")
            append("sys.exit(0 if d.dispatch(args) else 1)\" ")
            append(argStr)
        }
        return TerminalManager.shell(dulwichCmd, false, cwd?.absolutePath)
    }

    private fun embeddedGitPath(): String? {
        // 解压 res/raw/git_arm64 到 filesDir/bin/git 并设置可执行权限
        return runCatching {
            val outDir = File(ctx.filesDir, "bin").apply { mkdirs() }
            val outFile = File(outDir, "git")
            if (!outFile.exists() || outFile.length() < 1_000_000) {
                val id = ctx.resources.getIdentifier("git_arm64", "raw", ctx.packageName)
                if (id == 0) return null
                ctx.resources.openRawResource(id).use { inp -> outFile.outputStream().use { o -> inp.copyTo(o) } }
                TerminalManager.shell("chmod 755 ${outFile.absolutePath}", true)
            }
            outFile.absolutePath
        }.getOrNull()
    }
}
