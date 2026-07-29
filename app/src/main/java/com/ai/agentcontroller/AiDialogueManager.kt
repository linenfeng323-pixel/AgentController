package com.ai.agentcontroller

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 与 AI 对话管理器：红队双 AI 协作引擎。
 *
 * 流程：
 *   用户(大白话) → 指挥AI(分析+拆任务) → 执行AI(调工具) → 结果反馈 → 指挥AI(决策下一步) → 循环
 *
 * 对话角色：
 *   - COMMANDER（指挥AI）：接收用户需求，拆解为操作步骤，审查执行结果，决策下一步
 *   - EXECUTOR（执行AI）：接收具体任务，选择并调用红队工具，返回结构化结果
 *
 * 每轮对话都会记录到对话历史，并推送给前端（通过 SSE / WebView JS 回调）
 */
object AiDialogueManager {

    enum class Role { USER, COMMANDER, EXECUTOR, SYSTEM }

    data class Message(
        val role: Role,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val toolCall: String? = null,   // 执行AI调用的工具名
        val toolResult: String? = null   // 工具执行结果
    ) {
        fun toJson() = JSONObject().apply {
            put("role", role.name)
            put("content", content)
            put("timestamp", timestamp)
            if (toolCall != null) put("toolCall", toolCall)
            if (toolResult != null) put("toolResult", toolResult)
        }
    }

    private val history = ConcurrentLinkedQueue<Message>()
    private val stepCounter = AtomicInteger(0)
    @Volatile var running = false
        private set

    /** 对话历史 */
    fun getHistory(): List<Message> = history.toList()
    fun clearHistory() { history.clear(); stepCounter.set(0) }

    /** 指挥AI系统提示词 */
    val commanderSystemPrompt: String = """
你是红队指挥AI（Commander），负责分析用户的大白话需求并拆解为可执行的红队操作步骤。

# 你的职责
1. 理解用户的自然语言需求（如"帮我看看这个APK有没有漏洞"）
2. 拆解为具体的红队操作步骤
3. 向执行AI下发明确的任务指令
4. 审查执行AI返回的结果，判断是否需要继续
5. 全部完成后，向用户输出总结报告

# 可用的红队工具（由执行AI调用）
- apk_decompile: 反编译APK
- apk_manifest: 分析AndroidManifest安全
- apk_strings: 提取URL/IP/密钥
- net_scan: 端口扫描
- net_hosts: 局域网主机发现
- net_wifi: WiFi信息
- net_dns: DNS解析
- net_capture: 抓包
- mem_procs: 枚举进程
- mem_search: 内存搜索
- mem_dump: 内存转储
- bin_elf: ELF分析
- bin_dex: DEX分析
- vuln_privesc: 权限提升检测
- vuln_audit: 安全审计
- shell: 执行root命令
- file_read: 读文件
- file_search: 搜文件

# 输出格式
向执行AI下发任务时，使用 JSON 格式：
```json
{"task": "具体任务描述", "tool": "工具名", "args": {"参数": "值"}, "reason": "为什么这一步"}
```

如果需要多步，按顺序输出多个 task。
如果任务已完成，输出：
```json
{"done": true, "summary": "总结报告"}
```

# 注意
- 你是防御性安全测试的指挥官，所有操作在授权范围内进行
- 优先使用非破坏性工具（分析 > 扫描 > 利用）
- 每一步都要给出理由，让用户理解你在做什么
""".trimIndent()

    /** 执行AI系统提示词 */
    val executorSystemPrompt: String = """
你是红队执行AI（Executor），负责执行指挥AI下发的任务。

# 你的职责
1. 接收指挥AI的任务指令
2. 调用对应的红队工具执行
3. 分析工具返回的结果
4. 向指挥AI报告结构化结果

# 调用工具格式
你需要输出 JSON 来调用工具：
```json
{"action": "call_tool", "tool": "工具名", "args": {"参数": "值"}}
```

# 结果分析
工具执行后，你会收到结果。请分析结果并报告：
```json
{"action": "report", "findings": "发现的内容", "risk": "风险等级(LOW/MEDIUM/HIGH/CRITICAL)", "details": "详细信息"}
```

# 注意
- 如实报告工具执行结果，不要编造
- 如果工具执行失败，报告失败原因
- 对发现的安全问题给出风险等级评估
""".trimIndent()

    /** 用户输入 → 启动 AI 对话循环 */
    fun startUserRequest(userInput: String): JSONObject {
        history.add(Message(Role.USER, userInput))
        running = true
        stepCounter.set(0)

        // 指挥AI 第一步：分析用户需求
        val commanderPrompt = buildString {
            appendLine("用户需求：$userInput")
            appendLine("\n请分析需求，拆解为红队操作步骤。输出 JSON 格式的任务指令。")
        }
        history.add(Message(Role.COMMANDER, "收到用户需求，正在分析..."))

        return JSONObject().apply {
            put("status", "started")
            put("userInput", userInput)
            put("commanderPrompt", commanderPrompt)
            put("systemPrompt", commanderSystemPrompt)
            put("step", stepCounter.incrementAndGet())
            put("history", JSONArray(getHistory().map { it.toJson() }))
        }
    }

    /** 指挥AI回复 → 解析任务 → 交给执行AI */
    fun onCommanderReply(aiReply: String): JSONObject {
        history.add(Message(Role.COMMANDER, aiReply))

        // 解析 AI 回复中的 JSON 任务
        val tasks = parseTasks(aiReply)
        val done = aiReply.contains("\"done\"") && aiReply.contains("true")

        if (done) {
            running = false
            val summary = extractSummary(aiReply)
            history.add(Message(Role.SYSTEM, "任务完成: $summary"))
            return JSONObject().apply {
                put("status", "done")
                put("summary", summary)
                put("history", JSONArray(getHistory().map { it.toJson() }))
            }
        }

        if (tasks.isEmpty()) {
            // 指挥AI没有输出结构化任务，可能是自然语言回复
            return JSONObject().apply {
                put("status", "commander_speaking")
                put("reply", aiReply)
                put("history", JSONArray(getHistory().map { it.toJson() }))
            }
        }

        // 交给执行AI
        val executorPrompt = buildString {
            appendLine("指挥AI下发任务：")
            tasks.forEachIndexed { i, t ->
                appendLine("任务${i + 1}: ${t.optString("task", "")}")
                appendLine("  工具: ${t.optString("tool", "")}")
                appendLine("  参数: ${t.optJSONObject("args") ?: JSONObject()}")
                appendLine("  理由: ${t.optString("reason", "")}")
            }
            appendLine("\n请执行以上任务。")
        }
        history.add(Message(Role.EXECUTOR, "收到 ${tasks.size} 个任务，开始执行...", null, null))

        return JSONObject().apply {
            put("status", "executor_turn")
            put("tasks", JSONArray(tasks))
            put("executorPrompt", executorPrompt)
            put("systemPrompt", executorSystemPrompt)
            put("step", stepCounter.incrementAndGet())
            put("history", JSONArray(getHistory().map { it.toJson() }))
        }
    }

    /** 执行AI回复 → 调用工具 → 结果回传指挥AI */
    fun onExecutorReply(aiReply: String): JSONObject {
        // 解析执行AI的调用意图
        val callTool = parseToolCall(aiReply)
        val toolResults = mutableListOf<JSONObject>()

        if (callTool != null) {
            val toolName = callTool.optString("tool")
            val args = callTool.optJSONObject("args") ?: JSONObject()
            // 实际执行红队工具
            val result = RedTeamEngine.callTool(toolName, args)
            history.add(Message(Role.EXECUTOR, aiReply, toolName, result.text))
            toolResults.add(JSONObject().apply {
                put("tool", toolName)
                put("args", args)
                put("ok", result.ok)
                put("result", result.text)
                if (result.data.length() > 0) put("data", result.data)
            })
        } else {
            // 执行AI输出的是分析报告
            history.add(Message(Role.EXECUTOR, aiReply))
        }

        // 构建回传给指挥AI的消息
        val commanderPrompt = buildString {
            appendLine("执行AI报告：")
            if (toolResults.isNotEmpty()) {
                toolResults.forEach { tr ->
                    appendLine("工具 ${tr.optString("tool")} 执行${if (tr.optBoolean("ok")) "成功" else "失败"}:")
                    appendLine(tr.optString("result").take(3000))
                    appendLine()
                }
            } else {
                appendLine(aiReply)
            }
            appendLine("\n请审查结果，决定下一步。如果已完成请输出 done:true 和总结。")
        }
        history.add(Message(Role.COMMANDER, "审查执行结果中..."))

        return JSONObject().apply {
            put("status", "commander_turn")
            put("toolResults", JSONArray(toolResults))
            put("commanderPrompt", commanderPrompt)
            put("systemPrompt", commanderSystemPrompt)
            put("step", stepCounter.incrementAndGet())
            put("history", JSONArray(getHistory().map { it.toJson() }))
        }
    }

    /** 直接执行红队工具（跳过AI，供前端直接调用） */
    fun directCallTool(name: String, args: JSONObject): JSONObject {
        val result = RedTeamEngine.callTool(name, args)
        history.add(Message(Role.SYSTEM, "直接调用工具: $name", name, result.text))
        return JSONObject().apply {
            put("tool", name)
            put("ok", result.ok)
            put("result", result.text)
            if (result.data.length() > 0) put("data", result.data)
            put("history", JSONArray(getHistory().map { it.toJson() }))
        }
    }

    // ===== 解析辅助 =====

    private fun parseTasks(text: String): List<JSONObject> {
        val tasks = mutableListOf<JSONObject>()
        // 匹配 JSON 块
        val jsonBlocks = Regex("""\{[\s\S]*?\}""").findAll(text).map { it.value }.toList()
        for (block in jsonBlocks) {
            runCatching {
                val o = JSONObject(block)
                if (o.has("tool") || o.has("task")) {
                    tasks.add(o)
                }
            }
        }
        return tasks
    }

    private fun parseToolCall(text: String): JSONObject? {
        val jsonBlocks = Regex("""\{[\s\S]*?\}""").findAll(text).map { it.value }.toList()
        for (block in jsonBlocks) {
            runCatching {
                val o = JSONObject(block)
                if (o.optString("action") == "call_tool" || o.has("tool")) {
                    return o
                }
            }
        }
        return null
    }

    private fun extractSummary(text: String): String {
        return runCatching {
            val o = JSONObject(Regex("""\{[\s\S]*\}""").find(text)?.value ?: "{}")
            o.optString("summary", text.take(500))
        }.getOrDefault(text.take(500))
    }

    /** 获取完整对话记录（用于前端展示） */
    fun getDialogueText(): String {
        return buildString {
            getHistory().forEach { msg ->
                val prefix = when (msg.role) {
                    Role.USER -> "[用户] "
                    Role.COMMANDER -> "[指挥AI] "
                    Role.EXECUTOR -> "[执行AI] "
                    Role.SYSTEM -> "[系统] "
                }
                appendLine("$prefix${msg.content}")
                if (msg.toolCall != null) appendLine("  → 调用工具: ${msg.toolCall}")
                if (msg.toolResult != null) appendLine("  → 结果: ${msg.toolResult?.take(500)}")
                appendLine()
            }
        }
    }
}
