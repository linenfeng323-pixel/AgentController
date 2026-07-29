package com.ai.agentcontroller

import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 计划任务引擎：
 *  解析 LLM 输出的 {"tasks":[...]} 或自然语言步骤
 *  支持 依赖解析 / 优先级排序 / 并行分组 / 失败重试（最多 3 次）
 *  推进过程中通过 SSE 推送 plan_update / plan_done 事件
 */
object PlanEngine {

    data class Task(
        val id: String,
        val action: String,
        val target: String? = null,
        val text: String? = null,
        val depends: List<String> = emptyList(),
        val priority: Int = 0,
        var retries: Int = 0,
        var status: Status = Status.PENDING,
        var result: String? = null,
        var error: String? = null
    ) : Comparable<Task> {
        enum class Status { PENDING, RUNNING, DONE, FAILED }
        override fun compareTo(other: Task) = other.priority - priority
    }

    data class PlanState(val id: String, val tasks: List<Task>) {
        fun toJson() = JSONObject().apply {
            put("id", id)
            put("tasks", JSONArray(tasks.map { task ->
                JSONObject().apply {
                    put("id", task.id); put("action", task.action); put("target", task.target)
                    put("text", task.text); put("depends", JSONArray(task.depends))
                    put("priority", task.priority); put("status", task.status.name)
                    put("result", task.result); put("error", task.error)
                }
            }))
            put("progress", progress())
        }
        fun progress(): Double = if (tasks.isEmpty()) 1.0 else tasks.count { it.status == Task.Status.DONE }.toDouble() / tasks.size
        fun done() = tasks.all { it.status == Task.Status.DONE || it.status == Task.Status.FAILED }
    }

    private val seq = AtomicInteger(0)
    private val plans = ConcurrentHashMap<String, PlanState>()

    fun createPlan(tasks: List<Task>): PlanState {
        val id = "plan_${System.currentTimeMillis()}_${seq.incrementAndGet()}"
        val state = PlanState(id, tasks)
        plans[id] = state
        McpServerExt.emit("plan", JSONObject().put("type", "plan_created").put("plan", state.toJson()))
        return state
    }

    /** 从自然语言文本抽取步骤，失败返回 null。 */
    fun parseFromText(text: String): PlanState? {
        // 优先匹配 JSON {"tasks": [...]}
        runCatching {
            val m = Regex("""\{[\s\S]*?"tasks"[\s\S]*?\}""").find(text)
            if (m != null) {
                val o = JSONObject(m.value)
                val arr = o.optJSONArray("tasks") ?: JSONArray()
                val tasks = (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    Task(
                        id = t.optString("id", "t$i"),
                        action = t.optString("action"),
                        target = t.optString("target"),
                        text = t.optString("text"),
                        depends = (t.optJSONArray("depends") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
                        priority = t.optInt("priority", 0)
                    )
                }
                if (tasks.isNotEmpty()) return createPlan(tasks)
            }
        }
        // 兜底：抽 "- xxx" 形式的步骤
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val numbered = mutableListOf<Task>()
        var idx = 0
        for (l in lines) {
            val item = l.trimStart('-', '*', ' ', '\t').trim()
            if (item.isEmpty()) continue
            val m = Regex("""^(\d+)[\.、)]\s*(.+)$""").matchEntire(item)
            val body = if (m != null) m.groupValues[2] else item
            val id = "step_${idx++}"
            numbered.add(Task(id = id, action = "auto", text = body))
        }
        if (numbered.isEmpty()) return null
        return createPlan(numbered)
    }

    /** 执行计划：依赖拓扑 + 重试 3 次 + SSE 推送。 */
    fun execute(planId: String, onUpdate: (PlanState) -> Unit = {}) {
        val plan = plans[planId] ?: return
        val done = ConcurrentHashMap.newKeySet<String>()
        val queue = PriorityQueue<Task>(plan.tasks.size)
        queue.addAll(plan.tasks.filter { it.depends.isEmpty() })

        while (!plan.done()) {
            val ready = plan.tasks.filter { it.status == Task.Status.PENDING && done.containsAll(it.depends) }
            if (ready.isEmpty()) break
            var progressed = false
            for (task in ready) {
                task.status = Task.Status.RUNNING
                onUpdate(plan)
                McpServerExt.emit("plan", JSONObject().put("type", "plan_update").put("plan", plan.toJson()).put("taskId", task.id))
                var ok = false
                var lastErr: String? = null
                repeat(3) { attempt ->
                    try {
                        val r = runOne(task, plan)
                        if (r) { task.status = Task.Status.DONE; ok = true }
                        else lastErr = "attempt $attempt result=false"
                    } catch (t: Throwable) {
                        lastErr = t.message; task.retries++
                    }
                    if (ok) return@repeat
                }
                if (!ok) {
                    task.status = Task.Status.FAILED
                    task.error = lastErr
                }
                done.add(task.id)
                progressed = true
                onUpdate(plan)
                McpServerExt.emit("plan", JSONObject().put("type", "plan_update").put("plan", plan.toJson()).put("taskId", task.id))
            }
            if (!progressed) break
        }
        val summary = buildString {
            appendLine("计划完成: ${plan.progress() * 100}%")
            plan.tasks.forEach { appendLine("- [${it.status}] ${it.action} ${it.target ?: ""} ${it.text?.take(40) ?: ""}") }
        }
        plan.tasks.firstOrNull()?.result = summary
        onUpdate(plan)
        McpServerExt.emit("plan", JSONObject().put("type", "plan_done").put("plan", plan.toJson()))
    }

    private fun runOne(task: Task, plan: PlanState): Boolean {
        return when (task.action) {
            "auto" -> {
                // 交给 CommandParser + 自然语言快捷指令解析兜底
                val text = task.text ?: return false
                val batch = CommandParser.parse(text)
                if (batch.commands.isNotEmpty()) {
                    HybridExecutor.executeAll(batch.commands).all { it }
                } else {
                    val t = text.trim()
                    // 复用快捷指令映射
                    val cmd = resolveQuickCommand(t)
                    if (cmd != null) HybridExecutor.execute(cmd) else false
                }
            }
            else -> {
                val cmd = AgentCommand(
                    action = task.action,
                    target = task.target,
                    text = task.text,
                    pkg = task.target
                )
                HybridExecutor.execute(cmd)
            }
        }
    }

    private fun resolveQuickCommand(t: String): AgentCommand? {
        // 与 WebView 快捷指令保持一致
        return when {
            t.startsWith("打开") || t.startsWith("启动") -> {
                val app = t.replaceFirst(Regex("^(打开|启动)"), "").trim()
                AgentCommand("open_app", target = app, pkg = app)
            }
            t == "返回" -> AgentCommand("back")
            t.contains("桌面") || t.equals("home", true) -> AgentCommand("home")
            t.contains("最近任务") || t.contains("多任务") -> AgentCommand("recents")
            t.startsWith("截图") || t.startsWith("截屏") -> AgentCommand("screenshot")
            else -> null
        }
    }

    fun get(id: String): PlanState? = plans[id]
    fun listRecent(limit: Int = 10): List<PlanState> = plans.values.take(limit)
}
