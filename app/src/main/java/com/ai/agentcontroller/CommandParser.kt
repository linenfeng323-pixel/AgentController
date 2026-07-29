package com.ai.agentcontroller

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 AI 的自然语言/JSON 回复解析为 [CommandBatch]。
 *
 * 容错策略（按优先级）：
 *  1. 提取 ```json ... ``` 代码块并解析
 *  2. 提取第一个 { ... } 对象并解析
 *  3. 提取 [ ... ] 数组并当作 commands
 *  4. 全部失败时，把整段文本当成一条 notify 指令（让用户看到 AI 说了什么）
 *
 * 这样无论 AI 是否严格按格式输出，都能“可用”。
 */
object CommandParser {

    fun parse(rawReply: String): CommandBatch {
        if (rawReply.isBlank()) return CommandBatch("（空回复）", emptyList())

        // 1) ```json 代码块
        extractCodeBlock(rawReply)?.let { parsed ->
            return parsed
        }

        // 2) 第一个 JSON 对象
        extractJsonObject(rawReply)?.let { obj ->
            return parseObject(obj)
        }

        // 3) JSON 数组
        extractJsonArray(rawReply)?.let { arr ->
            val cmds = parseArray(arr)
            if (cmds.isNotEmpty()) return CommandBatch("从数组解析", cmds)
        }

        // 4) 兜底：当成提示
        return CommandBatch(rawReply.take(500), listOf(AgentCommand(action = "notify", target = rawReply.take(200))))
    }

    private fun parseObject(obj: JSONObject): CommandBatch {
        val explain = obj.optString("explain", obj.optString("reason", ""))
        val cmdsArr = obj.optJSONArray("commands") ?: obj.optJSONArray("steps") ?: obj.optJSONArray("actions")
        val cmds = if (cmdsArr != null) parseArray(cmdsArr) else emptyList()
        return CommandBatch(explain, cmds)
    }

    private fun parseArray(arr: JSONArray): List<AgentCommand> {
        val list = mutableListOf<AgentCommand>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            list += parseCommand(item)
        }
        return list
    }

    private fun parseCommand(o: JSONObject): AgentCommand {
        val action = o.optString("action", o.optString("type", "")).lowercase()
        return AgentCommand(
            action = action,
            target = o.optString("target", o.optString("selector", o.optString("view", ""))).ifBlank { null },
            text = o.optString("text", o.optString("value", "")).ifBlank { null },
            direction = o.optString("direction", "").ifBlank { null },
            amount = o.optInt("amount", o.optInt("value", 0)),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            ms = o.optLong("ms", o.optLong("duration", 300L)),
            pkg = o.optString("package", o.optString("pkg", "")).ifBlank { null }
        )
    }

    // ===== JSON 提取工具 =====

    private fun extractCodeBlock(raw: String): CommandBatch? {
        val regex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        for (m in regex.findAll(raw)) {
            val body = m.groupValues[1].trim()
            try {
                val obj = JSONObject(body)
                return parseObject(obj)
            } catch (_: Throwable) {
                try {
                    val arr = JSONArray(body)
                    val cmds = parseArray(arr)
                    if (cmds.isNotEmpty()) return CommandBatch("从代码块数组解析", cmds)
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return try { JSONObject(raw.substring(start, i + 1)) } catch (_: Throwable) { null }
                    }
                }
            }
        }
        return null
    }

    private fun extractJsonArray(raw: String): JSONArray? {
        val start = raw.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                !inStr && c == '[' -> depth++
                !inStr && c == ']' -> {
                    depth--
                    if (depth == 0) {
                        return try { JSONArray(raw.substring(start, i + 1)) } catch (_: Throwable) { null }
                    }
                }
            }
        }
        return null
    }
}
