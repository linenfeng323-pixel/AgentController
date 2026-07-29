package com.ai.agentcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 看板 / 用量分析 / 日志过滤 / 技能模板 / 上下文总结 / 后台复盘。
 *
 * 功能：
 *  - 看板：跨会话长期待办，AI 计划可一键导入
 *  - 用量分析：3 日趋势、模型分布、成本估算
 *  - 日志：按级别/关键词过滤
 *  - 技能模板：可复用的 prompt 模板库
 *  - 上下文总结 / 后台复盘
 */
object DashboardManager {

    private val ctx: Context get() = App.instance

    data class Todo(val id: String, val content: String, val done: Boolean, val createdAt: Long, val source: String = "") {
        fun toJson() = JSONObject().apply {
            put("id", id); put("content", content); put("done", done); put("createdAt", createdAt); put("source", source)
        }
        companion object {
            fun fromJson(o: JSONObject) = Todo(o.optString("id"), o.optString("content"), o.optBoolean("done"), o.optLong("createdAt"), o.optString("source"))
        }
    }

    // ===== 看板待办 =====

    private val todosFile: File get() = File(ctx.filesDir, "dashboard_todos.json")
    private val todos = mutableListOf<Todo>()

    fun listTodos(includeDone: Boolean = false): List<Todo> { loadTodos(); return if (includeDone) todos else todos.filter { !it.done } }
    fun addTodo(content: String, source: String = "") {
        loadTodos()
        val id = java.util.UUID.randomUUID().toString().take(8)
        todos.add(0, Todo(id, content, false, System.currentTimeMillis(), source))
        saveTodos()
    }
    fun doneTodo(id: String) {
        loadTodos()
        val i = todos.indexOfFirst { it.id == id }
        if (i >= 0) todos[i] = todos[i].copy(done = true)
        saveTodos()
    }
    fun deleteTodo(id: String) {
        loadTodos(); todos.removeAll { it.id == id }; saveTodos()
    }
    fun importFromAiPlan(planText: String) {
        // 从 AI 回复中抽取 "- xxx" 形式的计划项
        val lines = planText.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("*") || Regex("^\\d+\\.").matches(it) }
        lines.forEach { addTodo(it.trimStart('-', '*', ' ', '\t').trim(), source = "ai_plan") }
    }

    private fun loadTodos() {
        if (todos.isNotEmpty()) return
        if (!todosFile.exists()) return
        runCatching {
            val arr = JSONArray(todosFile.readText())
            for (i in 0 until arr.length()) todos.add(Todo.fromJson(arr.getJSONObject(i)))
        }
    }
    private fun saveTodos() { todosFile.writeText(JSONArray(todos.map { it.toJson() }).toString(2)) }

    // ===== 用量分析 =====

    data class UsageEntry(val model: String, val tokens: Long, val cost: Double, val ts: Long) {
        fun toJson() = JSONObject().apply {
            put("model", model); put("tokens", tokens); put("cost", cost); put("ts", ts)
        }
        companion object { fun fromJson(o: JSONObject) = UsageEntry(o.optString("model"), o.optLong("tokens"), o.optDouble("cost"), o.optLong("ts")) }
    }

    private val usageFile: File get() = File(ctx.filesDir, "usage.json")
    private val usage = mutableListOf<UsageEntry>()

    fun recordUsage(model: String, tokens: Long, cost: Double = 0.0) {
        loadUsage()
        usage.add(UsageEntry(model, tokens, cost, System.currentTimeMillis()))
        // 只保留 30 天
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        usage.removeAll { it.ts < cutoff }
        saveUsage()
    }

    fun usageSummary(days: Int = 3): JSONObject {
        loadUsage()
        val cutoff = System.currentTimeMillis() - days * 24 * 3600 * 1000
        val recent = usage.filter { it.ts >= cutoff }
        val byModel = recent.groupBy { it.model }.mapValues { e -> e.value.let { list -> Summary(list.sumOf { it.tokens }, list.sumOf { it.cost }, list.size) } }
        val byDay = recent.groupBy { it.ts / (24 * 3600 * 1000) }.mapValues { it.value.sumOf { it.tokens } }
        return JSONObject().apply {
            put("days", days)
            put("totalTokens", recent.sumOf { it.tokens })
            put("totalCost", recent.sumOf { it.cost })
            put("calls", recent.size)
            put("byModel", JSONObject(byModel.mapValues { it.value.toJson() }))
            put("byDay", JSONObject(byDay.mapKeys { it.key.toString() }))
        }
    }

    private data class Summary(val tokens: Long, val cost: Double, val calls: Int) {
        fun toJson() = JSONObject().apply { put("tokens", tokens); put("cost", cost); put("calls", calls) }
    }

    private fun loadUsage() {
        if (usage.isNotEmpty()) return
        if (!usageFile.exists()) return
        runCatching {
            val arr = JSONArray(usageFile.readText())
            for (i in 0 until arr.length()) usage.add(UsageEntry.fromJson(arr.getJSONObject(i)))
        }
    }
    private fun saveUsage() { usageFile.writeText(JSONArray(usage.map { it.toJson() }).toString(2)) }

    // ===== 日志过滤 =====

    data class LogFilter(val level: String? = null, val keyword: String? = null)

    fun filterLogs(filter: LogFilter): List<CommandLogManager.LogEntry> {
        return CommandLogManager.snapshot().filter { e ->
            (filter.level == null || e.level == filter.level) &&
            (filter.keyword == null || e.message.contains(filter.keyword, ignoreCase = true))
        }
    }

    // ===== 技能模板 =====

    data class SkillTemplate(val name: String, val prompt: String, val description: String) {
        fun toJson() = JSONObject().apply { put("name", name); put("prompt", prompt); put("description", description) }
        companion object { fun fromJson(o: JSONObject) = SkillTemplate(o.optString("name"), o.optString("prompt"), o.optString("description")) }
    }

    private val skillsFile: File get() = File(ctx.filesDir, "skill_templates.json")
    private val skills = mutableListOf<SkillTemplate>()

    fun listSkills(): List<SkillTemplate> { loadSkills(); return skills.toList() }
    fun addSkill(s: SkillTemplate) { loadSkills(); skills.add(0, s); saveSkills() }
    fun deleteSkill(name: String) { loadSkills(); skills.removeAll { it.name == name }; saveSkills() }

    /** 把技能注入到 AI 回复 prompt 里 */
    fun injectSkill(skillName: String, userGoal: String): String? {
        val s = listSkills().firstOrNull { it.name == skillName } ?: return null
        return "${s.prompt}\n\n用户目标：$userGoal"
    }

    private fun loadSkills() {
        if (skills.isNotEmpty()) return
        // 预置一些模板
        if (!skillsFile.exists()) {
            skills.addAll(listOf(
                SkillTemplate("代码审查", "你是一名严格的代码审查员，请审查下面的代码，指出潜在 bug、性能问题和不符合规范之处。", ""),
                SkillTemplate("中文翻译", "请把下面的英文内容翻译成地道中文，保留原意和专业术语。", ""),
                SkillTemplate("Bug 分析", "根据日志和错误堆栈，分析可能的根本原因并给出排查步骤。", ""),
                SkillTemplate("Shell 专家", "你是资深 Android/Linux Shell 专家，把用户需求转换成可执行的命令。", "")
            ))
            saveSkills()
        } else {
            runCatching {
                val arr = JSONArray(skillsFile.readText())
                for (i in 0 until arr.length()) skills.add(SkillTemplate.fromJson(arr.getJSONObject(i)))
            }
        }
    }
    private fun saveSkills() { skillsFile.writeText(JSONArray(skills.map { it.toJson() }).toString(2)) }

    // ===== 上下文总结 / 后台复盘 =====

    private val summariesFile: File get() = File(ctx.filesDir, "summaries.json")
    private val summaries = mutableListOf<String>()

    fun saveSummary(text: String) {
        summaries.add(0, text)
        if (summaries.size > 50) summaries.subList(50, summaries.size).clear()
        summariesFile.writeText(JSONArray(summaries).toString(2))
    }
    fun recentSummaries(limit: Int = 10): List<String> {
        if (summaries.isEmpty() && summariesFile.exists()) {
            runCatching { val arr = JSONArray(summariesFile.readText()); for (i in 0 until arr.length()) summaries.add(arr.optString(i)) }
        }
        return summaries.take(limit)
    }

    /** 后台复盘：汇总近期日志与待办，形成一段给 AI 的回顾材料 */
    fun generateReview(days: Int = 1): String {
        val logSummary = filterLogs(LogFilter()).take(20).joinToString("\n") { "[${it.level}] ${it.message}" }
        val openTodos = listTodos().take(10).joinToString("\n") { "- ${it.content}" }
        val usage = usageSummary(days)
        return buildString {
            appendLine("## 最近 $days 日复盘")
            appendLine("- 总 tokens: ${usage.optLong("totalTokens")}")
            appendLine("- 总调用: ${usage.optInt("calls")}")
            appendLine("- 总成本: ${usage.optDouble("totalCost")}")
            appendLine("\n## 待办")
            appendLine(openTodos.ifBlank { "无" })
            appendLine("\n## 日志摘要")
            appendLine(logSummary.ifBlank { "无" })
        }
    }
}
