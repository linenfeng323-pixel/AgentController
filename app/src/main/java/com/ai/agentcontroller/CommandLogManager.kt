package com.ai.agentcontroller

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局日志管理，UI 与执行流程都向这里写日志。
 */
object CommandLogManager {

    private val sb = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val listeners = mutableListOf<(String) -> Unit>()

    val fullText: String get() = sb.toString()

    fun log(tag: String, msg: String) {
        val line = "[${fmt.format(Date())}] $tag: $msg\n"
        sb.append(line)
        Log.d("AgentCtrl", line.trimEnd())
        val snapshot = sb.toString()
        listeners.forEach { runCatching { it(snapshot) } }
        // 防止无限增长
        if (sb.length > 40000) sb.delete(0, 20000)
    }

    fun info(msg: String) = log("INFO", msg)
    fun ok(msg: String) = log("OK", msg)
    fun warn(msg: String) = log("WARN", msg)
    fun err(msg: String) = log("ERR", msg)

    fun clear() {
        sb.clear()
        listeners.forEach { runCatching { it("") } }
    }

    fun observe(cb: (String) -> Unit) {
        listeners.add(cb)
        cb(sb.toString())
    }

    fun stopObserve(cb: (String) -> Unit) {
        listeners.remove(cb)
    }
}
