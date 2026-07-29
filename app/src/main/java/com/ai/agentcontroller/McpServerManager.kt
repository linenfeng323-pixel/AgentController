package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Server 管理器：
 *  - 内置本地 MCP 服务（文件、shell、无障碍、媒体等工具）
 *  - 支持通过 HTTP/SSE 对外暴露 MCP 协议
 *  - 可注册第三方 MCP 服务 URL
 *  - 供 AI 选择调用（"skill 管理" 的底层实现）
 */
object McpServerManager {

    private val ctx: Context get() = App.instance

    private val registered = ConcurrentHashMap<String, McpEntry>()

    data class McpEntry(
        val name: String,
        val url: String,          // 外部 MCP 地址；null 表示本地内置
        val description: String
    )

    private val port = 18899
    private var serverThread: Thread? = null
    @Volatile private var serverSocket: ServerSocket? = null

    fun listServers(): List<McpEntry> = registered.values.toList()

    fun register(entry: McpEntry) {
        registered[entry.name] = entry
        persist()
    }

    fun unregister(name: String) {
        registered.remove(name)
        persist()
    }

    /** 启动本地 MCP HTTP 服务（简易实现，兼容部分 MCP 协议）。 */
    fun startLocal(): Boolean {
        if (serverSocket != null) return true
        serverThread = Thread {
            runCatching {
                val ss = ServerSocket()
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                CommandLogManager.ok("MCP 服务已启动 :$port")
                while (!ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    handleClient(client)
                }
            }.onFailure { CommandLogManager.err("MCP 服务启动失败: ${it.message}") }
        }.also { it.start() }
        return true
    }

    fun stopLocal() {
        runCatching { serverSocket?.close() }
        serverThread?.interrupt()
        serverSocket = null
        CommandLogManager.info("MCP 服务已停止")
    }

    private fun handleClient(client: java.net.Socket) {
        runCatching {
            val input = client.inputStream.bufferedReader()
            val output = client.outputStream
            val lines = mutableListOf<String>()
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                lines.add(line!!)
            }
            val body = buildString { lines.forEach { append(it); append('\n') } }
            val first = lines.firstOrNull() ?: ""
            val (_, path) = first.split(" ", limit = 3).let { if (it.size >= 2) it[0] to it[1] else "" to "/" }

            val resp = when {
                path == "/mcp" -> handleMcp(body)
                path == "/health" -> "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}"
                else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            }
            output.write(resp.toByteArray())
            output.flush()
            client.close()
        }
    }

    private fun handleMcp(body: String): String {
        return try {
            val req = JSONObject(body)
            val method = req.optString("method")
            val params = req.optJSONObject("params")
            val id = req.opt("id") ?: JSONObject.NULL

            val result = when (method) {
                "initialize" -> JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", JSONObject().put("name", "AgentController-MCP").put("version", "1.0"))
                    put("capabilities", JSONObject().put("tools", JSONObject()))
                }
                "tools/list" -> listToolsJson()
                "tools/call" -> callTool(params)
                else -> JSONObject().put("error", "unknown method: $method")
            }
            val resp = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resp.toString().toByteArray().size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n${resp}"
        } catch (e: Throwable) {
            "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n"
        }
    }

    private fun listToolsJson(): JSONObject {
        val tools = JSONArray()
        tools.put(toolDef("shell", "在 Android user/root shell 执行命令", JSONObject().put("command", JSONObject().put("type","string")).put("root", JSONObject().put("type","boolean"))))
        tools.put(toolDef("open_app", "打开安卓应用", JSONObject().put("app", JSONObject().put("type","string"))))
        tools.put(toolDef("click", "点击屏幕文字或坐标", JSONObject().put("target", JSONObject().put("type","string"))))
        tools.put(toolDef("input_text", "输入文本", JSONObject().put("text", JSONObject().put("type","string"))))
        tools.put(toolDef("screenshot", "截图", JSONObject()))
        tools.put(toolDef("set_volume", "设置音量", JSONObject().put("value", JSONObject().put("type","integer"))))
        tools.put(toolDef("set_wifi", "开关 WiFi", JSONObject().put("on", JSONObject().put("type","boolean"))))
        tools.put(toolDef("read_file", "读取手机文件", JSONObject().put("path", JSONObject().put("type","string"))))
        tools.put(toolDef("write_file", "写入手机文件", JSONObject().put("path", JSONObject().put("type","string")).put("content", JSONObject().put("type","string"))))
        tools.put(toolDef("list_files", "列出目录", JSONObject().put("path", JSONObject().put("type","string"))))
        tools.put(toolDef("get_device_info", "获取设备信息", JSONObject()))
        return JSONObject().put("tools", tools)
    }

    private fun toolDef(name: String, desc: String, schema: JSONObject) = JSONObject().apply {
        put("name", name)
        put("description", desc)
        put("inputSchema", JSONObject().put("type","object").put("properties", schema))
    }

    private fun callTool(params: JSONObject?): JSONObject {
        val name = params?.optString("name") ?: return JSONObject().put("error","missing name")
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        val text = runCatching {
            when (name) {
                "shell" -> {
                    val cmd = args.optString("command")
                    val useRoot = args.optBoolean("root", true)
                    val r = TerminalManager.shell(cmd, useRoot)
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", r.out.ifBlank { r.err })))
                }
                "open_app" -> {
                    val r = AppLauncher.launch(args.optString("app"))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", if (r) "已启动" else "启动失败")))
                }
                "click" -> {
                    val r = HybridExecutor.execute(AgentCommand("click", target = args.optString("target")))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", if (r) "已点击" else "点击失败")))
                }
                "input_text" -> {
                    val r = HybridExecutor.execute(AgentCommand("input_text", text = args.optString("text")))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", if (r) "已输入" else "输入失败")))
                }
                "screenshot" -> {
                    val f = File(ctx.cacheDir, "mcp_shot_${System.currentTimeMillis()}.png")
                    val ok = RootShellExecutor.screenshot(f)
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", if (ok) f.absolutePath else "截图失败")))
                }
                "set_volume" -> {
                    DeviceControlManager.setVolume(args.optInt("value"))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text","已设置音量")))
                }
                "set_wifi" -> {
                    val ok = DeviceControlManager.setWifi(args.optBoolean("on", true))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", if (ok) "成功" else "失败")))
                }
                "read_file" -> {
                    val text = TerminalManager.readFile(args.optString("path"))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", text)))
                }
                "write_file" -> {
                    val ok = TerminalManager.writeFile(args.optString("path"), args.optString("content"))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", if (ok) "写入成功" else "写入失败")))
                }
                "list_files" -> {
                    val items = TerminalManager.list(args.optString("path"))
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", items.joinToString("\n"))))
                }
                "get_device_info" -> {
                    val info = JSONObject()
                    info.put("brand", android.os.Build.BRAND)
                    info.put("model", android.os.Build.MODEL)
                    info.put("sdk", android.os.Build.VERSION.SDK_INT)
                    info.put("root", RootShellExecutor.checkRoot())
                    info.put("packages", RootShellExecutor.listPackages().size)
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type","text").put("text", info.toString(2))))
                }
                else -> JSONObject().put("error", "unknown tool: $name")
            }
        }.getOrElse { JSONObject().put("error", it.message ?: "exec error") }
        return text
    }

    // ===== 持久化 =====

    private val confFile: File get() = File(ctx.filesDir, "mcp_servers.json")

    private fun persist() {
        val arr = JSONArray()
        registered.values.forEach { arr.put(it.toJson()) }
        confFile.writeText(arr.toString(2))
    }

    private fun McpEntry.toJson() = JSONObject().apply {
        put("name", name); put("url", url); put("description", description)
    }

    fun load() {
        if (!confFile.exists()) return
        runCatching {
            val arr = JSONArray(confFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                registered[o.optString("name")] = McpEntry(o.optString("name"), o.optString("url"), o.optString("description"))
            }
        }
    }
}
