package com.ai.agentcontroller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.ai.agentcontroller.AgentAccessibilityService.Companion.instance

/**
 * 基于无障碍服务的执行器。root 不可用时作为主路径，root 可用时作为补充
 * （尤其是中文输入、按文本/ID 找节点点击这类 root input 做不到的能力）。
 */
object AccessibilityExecutor {

    private val svc: AccessibilityService? get() = instance

    private val cm: ClipboardManager? by lazy {
        App.instance.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    /** 在当前屏幕中找到第一个文本包含 [text] 的可点击节点。 */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = svc?.rootInActiveWindow ?: return null
        return findByText(root, text)
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = node.findAccessibilityNodeInfosByText(text)
        list?.firstOrNull { it.isVisibleToUser }?.let { return it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByText(child, text)?.let { return it }
        }
        return null
    }

    /** 点击“文本包含 text”的节点（逐级向上找可点击父节点）。 */
    fun clickText(text: String): Boolean {
        val node = findNodeByText(text) ?: return clickNodeById(text)
        return clickNode(node)
    }

    /** 点击 viewId（传入完整 id 如 com.tencent.mm:id/xxx 或短名）。 */
    fun clickNodeById(idHint: String): Boolean {
        val root = svc?.rootInActiveWindow ?: return false
        val node = findById(root, idHint) ?: return false
        return clickNode(node)
    }

    private fun findById(node: AccessibilityNodeInfo, idHint: String): AccessibilityNodeInfo? {
        val full = if (idHint.contains(":id/")) idHint else idHint
        val list = node.findAccessibilityNodeInfosByViewId(full)
        if (list?.isNotEmpty() == true) return list[0]
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findById(child, idHint)?.let { return it }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n.parent
        }
        // 兜底：用坐标点击
        val r = Rect()
        node.getBoundsInScreen(r)
        return tapScreen(r.exactCenterX(), r.exactCenterY())
    }

    /** 用坐标点击（root 优先，无障碍手势兜底）。 */
    fun tapScreen(x: Float, y: Float): Boolean {
        if (RootShellExecutor.checkRoot()) {
            return RootShellExecutor.tap(x, y).ok
        }
        val s = svc ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = AccessibilityService.GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = AccessibilityService.GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    /** 长按坐标。 */
    fun longPressScreen(x: Float, y: Float, durationMs: Long = 800): Boolean {
        if (RootShellExecutor.checkRoot()) {
            return RootShellExecutor.longPress(x, y, durationMs.toInt()).ok
        }
        val s = svc ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = AccessibilityService.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = AccessibilityService.GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    /** 滑动（root 优先）。 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        if (RootShellExecutor.checkRoot()) {
            return RootShellExecutor.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs.toInt()).ok
        }
        val s = svc ?: return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = AccessibilityService.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = AccessibilityService.GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    fun swipeDirection(direction: String, amount: Int = 500): Boolean {
        if (RootShellExecutor.checkRoot()) {
            return RootShellExecutor.swipeDirection(direction, amount).ok
        }
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> floatArrayOf(540f, 1800f, 540f, 1800f - amount)
            "down" -> floatArrayOf(540f, 400f, 540f, 400f + amount)
            "left" -> floatArrayOf(900f, 1000f, 900f - amount, 1000f)
            "right" -> floatArrayOf(180f, 1000f, 180f + amount, 1000f)
            else -> floatArrayOf(540f, 1200f, 540f, 1200f - amount)
        }
        return swipe(x1, y1, x2, y2)
    }

    /**
     * 输入文本（支持中文）。
     * 策略：先点聚焦目标节点（若给了 target），再用剪贴板 + 粘贴；
     * 若无 root 也无焦点节点，则回退到 root input text。
     */
    fun inputText(text: String, target: String? = null): Boolean {
        if (target != null) {
            val node = findNodeByText(target) ?: findById(svc?.rootInActiveWindow ?: return false, target)
            if (node != null) {
                clickNode(node)
                Thread.sleep(120)
                val focused = svc?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
            }
        }
        // 剪贴板粘贴
        cm?.let {
            it.setPrimaryClip(ClipData.newPlainText("ai", text))
            if (RootShellExecutor.checkRoot()) {
                RootShellExecutor.keyEvent(279) // KEYCODE_PASTE
                return true
            }
        }
        // 兜底
        return RootShellExecutor.inputText(text).ok
    }

    /** 返回 / Home / 最近任务。 */
    fun back(): Boolean {
        if (RootShellExecutor.checkRoot()) return RootShellExecutor.back().ok
        return svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }

    fun home(): Boolean {
        if (RootShellExecutor.checkRoot()) return RootShellExecutor.home().ok
        return svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }

    fun recents(): Boolean {
        if (RootShellExecutor.checkRoot()) return RootShellExecutor.recents().ok
        return svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
    }

    /** 读取当前屏幕全部可见文本（用于 AI 观察屏幕）。 */
    fun dumpScreenText(): String {
        val root = svc?.rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text
        val desc = node.contentDescription
        val id = node.viewIdResourceName
        val cls = node.className?.toString()?.substringAfterLast('.')
        val clickable = if (node.isClickable) "[可点]" else ""
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || id != null) {
            sb.append(indent)
            if (cls != null) sb.append("<$cls>")
            if (id != null) sb.append(" id=$id")
            if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
            if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
            sb.append(" $clickable\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1)
        }
    }

    /** 启动 App。 */
    fun launchApp(pkg: String): Boolean {
        if (RootShellExecutor.checkRoot()) return RootShellExecutor.launchApp(pkg).ok
        val ctx = App.instance
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true
    }
}
