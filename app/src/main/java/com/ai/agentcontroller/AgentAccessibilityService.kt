package com.ai.agentcontroller

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务。
 *
 * 职责：
 *  - 暴露静态 [instance]，供 [AccessibilityExecutor] 拿到根节点与全局动作
 *  - 监听窗口变化，记录当前前台包名（供 AI 观察与诊断）
 *  - 把事件转化为结构化日志，便于“自动观察屏幕”
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
        @Volatile var currentPackage: String = ""
            private set
        @Volatile var lastEventText: String = ""
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CommandLogManager.ok("无障碍服务已连接")
    }

    override fun onDestroy() {
        instance = null
        CommandLogManager.warn("无障碍服务已断开")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        currentPackage = pkg
        val text = event.text?.joinToString(" ")?.ifBlank { null }
        if (!text.isNullOrBlank()) {
            lastEventText = text.take(200)
            // 仅记录窗口变化与文本变化，避免日志过载
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // 由 UI 决定是否记录，这里只保留最新值，避免噪声
                }
            }
        }
    }

    override fun onInterrupt() {
        CommandLogManager.warn("无障碍服务被中断")
    }

    /** 主动刷新当前屏幕的完整文本（供代理循环观察用）。 */
    fun dumpCurrentScreen(): String = AccessibilityExecutor.dumpScreenText()
}
