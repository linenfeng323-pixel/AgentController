package com.ai.agentcontroller

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 严格对齐 agent-toolbox 的 DeepSeekChatBridge：
 *  - 单例跨 Activity 通信
 *  - 每轮独立 requestId 并发管理（最多 5 路并发）
 *  - WebView 前端 500ms MutationObserver 轮询 + 文本稳定 1.5s 后标记完成
 *  - Java 端 120s 硬超时 + 30s DOM 兜底提取
 *
 *  数据流：
 *   DeepSeek WebView (JS MutationObserver 每 500ms 采样)
 *    → AndroidBridge.onAiStream(rid, chunk, status)
 *     → DeepSeekChatBridge 分发
 *      → SSE 推送给所有 MCP 订阅者 (started / chunk / status / done / error)
 */
object DeepSeekChatBridge {

    data class Session(
        val requestId: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastChunkAt: Long = System.currentTimeMillis(),
        @Volatile var stableCount: Int = 0,
        @Volatile var done: Boolean = false,
        val buffer: StringBuilder = StringBuilder()
    )

    private const val POLL_MS = 500L
    private const val STABLE_MS = 1500L  // 3 次 500ms 不变 → 稳定
    private const val TIMEOUT_MS = 120_000L
    private const val FALLBACK_MS = 30_000L

    private val sessions = ConcurrentHashMap<String, Session>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)
    private const val MAX_CONCURRENT = 5

    fun newRequestId(): String = "req_${System.currentTimeMillis()}_${seq.incrementAndGet()}"

    /** AI 开始回复 */
    fun onStarted(rid: String) {
        if (sessions.size >= MAX_CONCURRENT) {
            // 强制完成最早的
            val oldest = sessions.values.minByOrNull { it.createdAt }
            if (oldest != null) finish(oldest.requestId, "cancel", "并发上限")
        }
        sessions[rid] = Session(rid)
        McpServerV2.emitSse("started", JSONObject().put("requestId", rid))
        scheduleWatchdog(rid)
    }

    /** AI 逐字流输出 */
    fun onChunk(rid: String, chunk: String) {
        val s = sessions[rid] ?: return
        val now = System.currentTimeMillis()
        s.lastChunkAt = now
        // 文本变化 → 重置 stable 计数
        if (!chunk.isBlank() && !s.buffer.endsWith(chunk)) {
            s.stableCount = 0
        } else {
            s.stableCount++
        }
        s.buffer.append(chunk)
        McpServerV2.emitSse("chunk", JSONObject().apply {
            put("requestId", rid); put("delta", chunk)
            put("text", s.buffer.toString().takeLast(5000))
        })
    }

    /** 文本稳定 1.5s 以上 → 视为 done；否则继续 */
    fun onStable(rid: String, fullText: String) {
        val s = sessions[rid] ?: return
        s.stableCount++
        if (s.stableCount * POLL_MS >= STABLE_MS || fullText.isNotBlank() && s.stableCount >= 3) {
            finish(rid, "done", fullText)
        }
    }

    /** 120s 超时 / 30s 兜底 DOM 提取 */
    private fun scheduleWatchdog(rid: String) {
        val start = System.currentTimeMillis()
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val s = sessions[rid] ?: return
                if (s.done) return
                val age = System.currentTimeMillis() - s.createdAt
                val sinceChunk = System.currentTimeMillis() - s.lastChunkAt
                when {
                    age > TIMEOUT_MS -> finish(rid, "timeout", "120s 超时")
                    sinceChunk > FALLBACK_MS && s.buffer.isNotBlank() -> {
                        // 30s 兜底 DOM 提取
                        finish(rid, "fallback_done", s.buffer.toString())
                    }
                    else -> mainHandler.postDelayed(this, 1000L)
                }
            }
        }, 1000L)
    }

    private fun finish(rid: String, status: String, text: String) {
        val s = sessions.remove(rid) ?: return
        if (s.done) return
        s.done = true
        McpServerV2.emitSse(status, JSONObject().apply {
            put("requestId", rid); put("text", text.takeLast(10_000))
        })
        McpServerV2.emitSse("done", JSONObject().apply {
            put("requestId", rid); put("text", text.takeLast(10_000))
            put("duration_ms", System.currentTimeMillis() - s.createdAt)
        })
    }

    /** 外部主动取消 */
    fun cancel(rid: String) = finish(rid, "cancel", "cancelled")

    fun activeSessions(): Int = sessions.size
}
