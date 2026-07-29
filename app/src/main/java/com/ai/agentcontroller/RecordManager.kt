package com.ai.agentcontroller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 命令历史与录制回放。
 *
 * - 录制：把 [AgentCommand] 序列化追加到当前录制文件
 * - 回放：读取录制文件，按原顺序与延时重新执行
 * - 历史：保存最近 N 次执行的批次，便于查看与一键重跑
 *
 * 借鉴白给 v19 “固定槽位测试”思路，支持给录制命名并独立保存，不被覆盖。
 */
object RecordManager {

    private fun historyDir(): File = File(App.instance.filesDir, "history").apply { if (!exists()) mkdirs() }
    private fun recordsDir(): File = File(App.instance.filesDir, "records").apply { if (!exists()) mkdirs() }

    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingStartTs: Long = 0L
    @Volatile private var lastCmdTs: Long = 0L

    val isRecording: Boolean get() = recordingFile != null

    fun startRecord(name: String): Boolean {
        if (recordingFile != null) {
            CommandLogManager.warn("已在录制中")
            return false
        }
        val safeName = if (name.isBlank()) "record_${System.currentTimeMillis()}" else name
        val f = File(recordsDir, "$safeName.json")
        recordingFile = f
        recordingStartTs = System.currentTimeMillis()
        lastCmdTs = recordingStartTs
        f.writeText(JSONObject().put("name", safeName).put("start", recordingStartTs).put("commands", JSONArray()).toString())
        CommandLogManager.ok("开始录制: $safeName")
        return true
    }

    fun record(cmd: AgentCommand) {
        val f = recordingFile ?: return
        try {
            val now = System.currentTimeMillis()
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("commands") ?: JSONArray()
            val item = JSONObject().apply {
                put("delayMs", now - lastCmdTs)
                put("action", cmd.action)
                cmd.target?.let { put("target", it) }
                cmd.text?.let { put("text", it) }
                cmd.direction?.let { put("direction", it) }
                if (cmd.amount != 0) put("amount", cmd.amount)
                if (cmd.x != 0f) put("x", cmd.x)
                if (cmd.y != 0f) put("y", cmd.y)
                if (cmd.ms != 300L) put("ms", cmd.ms)
                cmd.pkg?.let { put("pkg", it) }
            }
            arr.put(item)
            obj.put("commands", arr)
            f.writeText(obj.toString())
            lastCmdTs = now
        } catch (e: Throwable) {
            CommandLogManager.err("录制写入失败: ${e.message}")
        }
    }

    fun stopRecord(): File? {
        val f = recordingFile
        recordingFile = null
        if (f != null) CommandLogManager.ok("结束录制: ${f.name}")
        return f
    }

    fun listRecords(): List<String> = recordsDir.listFiles()?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    /** 回放指定录制名。 */
    fun replay(name: String): Boolean {
        val f = File(recordsDir, "$name.json")
        if (!f.exists()) {
            CommandLogManager.warn("录制不存在: $name")
            return false
        }
        try {
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("commands") ?: return false
            CommandLogManager.info("开始回放: $name (${arr.length()} 条)")
            Thread {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val delay = item.optLong("delayMs", 0)
                    if (delay > 0) try { Thread.sleep(delay.coerceAtMost(5000)) } catch (_: Throwable) {}
                    val cmd = AgentCommand(
                        action = item.optString("action"),
                        target = item.optString("target", "").ifBlank { null },
                        text = item.optString("text", "").ifBlank { null },
                        direction = item.optString("direction", "").ifBlank { null },
                        amount = item.optInt("amount", 0),
                        x = item.optDouble("x", 0.0).toFloat(),
                        y = item.optDouble("y", 0.0).toFloat(),
                        ms = item.optLong("ms", 300L),
                        pkg = item.optString("pkg", "").ifBlank { null }
                    )
                    HybridExecutor.execute(cmd)
                }
                CommandLogManager.ok("回放完成: $name")
            }.start()
            return true
        } catch (e: Throwable) {
            CommandLogManager.err("回放失败: ${e.message}")
            return false
        }
    }

    // ===== 命令历史 =====

    fun saveHistory(goal: String, batch: CommandBatch, results: List<Boolean>) {
        try {
            val o = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("goal", goal)
                put("explain", batch.explain)
                val cmds = JSONArray()
                batch.commands.forEachIndexed { i, c ->
                    val r = results.getOrNull(i) ?: false
                    cmds.put(JSONObject().apply {
                        put("action", c.action)
                        c.target?.let { put("target", it) }
                        c.text?.let { put("text", it) }
                        put("ok", r)
                    })
                }
                put("commands", cmds)
                put("okCount", results.count { it })
                put("total", results.size)
            }
            val f = File(historyDir(), "h_${System.currentTimeMillis()}.json")
            f.writeText(o.toString())
            // 保留最近 50 条
            historyDir().listFiles()?.sortedByDescending { it.lastModified() }?.drop(50)?.forEach { it.delete() }
        } catch (e: Throwable) {
            CommandLogManager.err("保存历史失败: ${e.message}")
        }
    }

    fun listHistory(): List<File> = historyDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun historySummary(): String {
        val list = listHistory().take(10)
        if (list.isEmpty()) return "（暂无历史）"
        val sb = StringBuilder()
        list.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                sb.appendLine("${f.name} | ${o.optString("goal").take(40)} | ${o.optInt("okCount")}/${o.optInt("total")}")
            } catch (_: Throwable) {}
        }
        return sb.toString()
    }
}
