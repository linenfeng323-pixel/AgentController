package com.ai.agentcontroller

/**
 * 自适应节流器。
 *
 * 借鉴白给 v19 “手机端自适应降频”思路：
 *  - 连续失败时自动降频，减少冲击
 *  - 连续成功时缓慢升频，恢复吞吐
 *  - 限定最小/最大间隔，防止极端值
 *
 * 用于代理循环、屏幕轮询、坐标解算等需要根据设备状态动态调节的场景。
 */
class AdaptiveThrottle(
    private val minIntervalMs: Long = 80L,
    private val maxIntervalMs: Long = 1500L,
    private val stepMs: Long = 50L,
    private val startIntervalMs: Long = 300L
) {
    @Volatile private var current = startIntervalMs
    @Volatile private var lastRun = 0L
    @Volatile private var consecutiveFail = 0
    @Volatile private var consecutiveOk = 0

    val interval: Long get() = current

    /** 是否已到下一次执行时间。 */
    fun shouldRunNow(now: Long = System.currentTimeMillis()): Boolean {
        return now - lastRun >= current
    }

    /** 记录一次执行（无论成败），并更新最近执行时间。 */
    fun markRun(now: Long = System.currentTimeMillis()) { lastRun = now }

    /** 执行成功：缓慢升频。 */
    fun onSuccess() {
        consecutiveOk++
        consecutiveFail = 0
        if (consecutiveOk >= 3 && current > minIntervalMs) {
            current = (current - stepMs).coerceAtLeast(minIntervalMs)
            consecutiveOk = 0
            CommandLogManager.log("THROTTLE", "升频 -> ${current}ms")
        }
    }

    /** 执行失败：快速降频。 */
    fun onFailure() {
        consecutiveFail++
        consecutiveOk = 0
        if (consecutiveFail >= 2 && current < maxIntervalMs) {
            current = (current + stepMs * 2).coerceAtMost(maxIntervalMs)
            CommandLogManager.warn("降频 -> ${current}ms")
        }
    }

    /** 阻塞等待到下一次执行时间。 */
    fun awaitNext() {
        val now = System.currentTimeMillis()
        val wait = current - (now - lastRun)
        if (wait > 0) {
            try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        }
        lastRun = System.currentTimeMillis()
    }

    fun reset() {
        current = startIntervalMs
        consecutiveFail = 0
        consecutiveOk = 0
        lastRun = 0L
    }
}
