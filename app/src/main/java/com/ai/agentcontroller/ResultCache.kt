package com.ai.agentcontroller

import java.util.concurrent.ConcurrentHashMap

/**
 * 通用任务缓存引擎。
 *
 * 借鉴白给 v19 解密防漏人策略：
 *  - 短期刷新窗口 [refreshMs]：超过则视为需要重新获取
 *  - 有效保留期 [keepMs]：过期前仍可使用上次结果，避免抖动
 *  - 最大过期期 [maxStaleMs]：超过则彻底丢弃，防止用过期数据
 *  - 任务排队超时 [queueTimeoutMs]：超过则丢弃任务，防止堆积
 *
 * 用于：AI 回复缓存、屏幕快照、坐标解算结果等“需要稳定且不丢”的场景。
 */
class ResultCache<K : Any, V>(
    private val refreshMs: Long = 100L,
    private val keepMs: Long = 1500L,
    private val maxStaleMs: Long = 5000L,
    private val queueTimeoutMs: Long = 1500L
) {
    private data class Entry<V>(
        val value: V,
        val bornAt: Long = System.currentTimeMillis()
    ) {
        fun age(now: Long) = now - bornAt
        fun needRefresh(now: Long, refreshMs: Long) = age(now) >= refreshMs
        fun stillUsable(now: Long, keepMs: Long) = age(now) < keepMs
        fun dead(now: Long, maxStaleMs: Long) = age(now) >= maxStaleMs
    }

    private val cache = ConcurrentHashMap<K, Entry<V>>()
    private val pendingSince = ConcurrentHashMap<K, Long>()

    /** 取值：若仍在保留期内直接返回，避免重复计算。 */
    fun get(key: K): V? {
        val now = System.currentTimeMillis()
        val e = cache[key] ?: return null
        if (e.dead(now, maxStaleMs)) {
            cache.remove(key)
            return null
        }
        return if (e.stillUsable(now, keepMs)) e.value else null
    }

    /** 是否需要重新获取（无值或超过刷新窗口）。 */
    fun needRefresh(key: K): Boolean {
        val now = System.currentTimeMillis()
        val e = cache[key] ?: return true
        return e.needRefresh(now, refreshMs)
    }

    /** 是否允许提交新任务（防止排队过久）。 */
    fun canSubmit(key: K): Boolean {
        val now = System.currentTimeMillis()
        val since = pendingSince[key]
        if (since != null && now - since > queueTimeoutMs) {
            // 排队超时，丢弃旧任务标记，允许重试
            pendingSince.remove(key, since)
            CommandLogManager.warn("任务排队超时被丢弃: $key")
        }
        return pendingSince.putIfAbsent(key, now) == null
    }

    fun markDone(key: K) {
        pendingSince.remove(key)
    }

    fun put(key: K, value: V) {
        cache[key] = Entry(value)
        pendingSince.remove(key)
    }

    fun remove(key: K) {
        cache.remove(key)
        pendingSince.remove(key)
    }

    fun clear() {
        cache.clear()
        pendingSince.clear()
    }

    /** 清理过期项，避免内存增长。 */
    fun gc() {
        val now = System.currentTimeMillis()
        val it = cache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.dead(now, maxStaleMs)) it.remove()
        }
    }

    fun stats(): String {
        val now = System.currentTimeMillis()
        val total = cache.size
        val fresh = cache.values.count { !it.needRefresh(now, refreshMs) }
        val pending = pendingSince.size
        return "cache: total=$total fresh=$fresh pending=$pending"
    }
}
