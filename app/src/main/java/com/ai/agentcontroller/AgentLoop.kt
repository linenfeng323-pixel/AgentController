package com.ai.agentcontroller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 代理循环引擎：观察 → 思考 → 行动。
 *
 * 工作流：
 *  1. 观察：调用 [observer] 获取当前环境（屏幕文本 / 截图 / AI 回复 / 任意状态）
 *  2. 思考：调用 [thinker] 把“用户目标 + 观察结果”交给 AI，得到 [CommandBatch]
 *  3. 行动：用 [HybridExecutor] 顺序执行指令；成功/失败反馈给节流器
 *
 *  - 自适应降频：连续失败自动放慢，连续成功缓慢加快（借鉴白给 v19）
 *  - 任务缓存：观察结果短期复用，避免抖动与漏操作（借鉴白给防漏人）
 *  - 可中断：[stop] 立即停止；[isRunning] 查询状态
 *  - 最大步数与超时保护，防止死循环
 */
class AgentLoop(
    private val observer: suspend () -> String,
    private val thinker: suspend (goal: String, observation: String) -> CommandBatch,
    private val onStep: (step: Int, explain: String, results: List<Boolean>) -> Unit = { _, _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile var isRunning = false
        private set

    private val throttle = AdaptiveThrottle()
    private val screenCache = ResultCache<String, String>(refreshMs = 200, keepMs = 800, maxStaleMs = 3000)

    /** 启动代理循环。 */
    fun start(goal: String, maxSteps: Int = 30, stepTimeoutMs: Long = 20_000L) {
        if (isRunning) {
            CommandLogManager.warn("代理循环已在运行")
            return
        }
        isRunning = true
        CommandLogManager.info("代理循环启动 | 目标: $goal | 最大步数: $maxSteps")
        job = scope.launch {
            try {
                for (step in 1..maxSteps) {
                    if (!isActive) break
                    CommandLogManager.log("LOOP", "===== 步骤 $step/$maxSteps =====")
                    val stepStart = System.currentTimeMillis()

                    // 1) 观察
                    val observation = try {
                        val key = "screen"
                        if (screenCache.needRefresh(key)) {
                            val v = observer()
                            screenCache.put(key, v)
                            v
                        } else {
                            screenCache.get(key) ?: observer()
                        }
                    } catch (e: Throwable) {
                        CommandLogManager.err("观察失败: ${e.message}")
                        throttle.onFailure()
                        throttle.awaitNext()
                        continue
                    }

                    // 2) 思考
                    val batch = try {
                        thinker(goal, observation)
                    } catch (e: Throwable) {
                        CommandLogManager.err("思考失败: ${e.message}")
                        throttle.onFailure()
                        throttle.awaitNext()
                        continue
                    }
                    CommandLogManager.info("AI 计划: ${batch.explain.ifBlank { "(无说明)" }}")

                    // 3) 行动
                    val results = if (batch.commands.isEmpty()) {
                        CommandLogManager.warn("无指令可执行")
                        emptyList()
                    } else {
                        HybridExecutor.executeAll(batch.commands)
                    }
                    onStep(step, batch.explain, results)

                    val successRate = if (results.isEmpty()) 0f else results.count { it }.toFloat() / results.size
                    if (successRate >= 0.5f) throttle.onSuccess() else throttle.onFailure()

                    // 目标完成判断（简单版：AI 没有产出指令且说明非空 → 视为完成）
                    if (batch.commands.isEmpty() && batch.explain.isNotBlank()) {
                        CommandLogManager.ok("代理循环完成: ${batch.explain}")
                        break
                    }

                    val cost = System.currentTimeMillis() - stepStart
                    if (cost > stepTimeoutMs) {
                        CommandLogManager.warn("单步超时 ${cost}ms")
                    }
                    throttle.awaitNext()
                }
                if (isRunning) CommandLogManager.ok("代理循环结束（达到最大步数）")
            } catch (e: Throwable) {
                CommandLogManager.err("代理循环异常: ${e.message}")
            } finally {
                isRunning = false
            }
        }
    }

    /** 停止代理循环。 */
    fun stop() {
        CommandLogManager.info("停止代理循环")
        job?.cancel()
        isRunning = false
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
