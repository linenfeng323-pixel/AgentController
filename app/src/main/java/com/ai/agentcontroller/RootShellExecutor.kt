package com.ai.agentcontroller

import android.text.TextUtils
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 系统级执行器。
 *
 * 通过 `su` 执行 shell 命令，实现真正的系统级操控：
 *  - input tap / swipe / text / keyevent  点击、滑动、输入、按键
 *  - am start / force-stop / kill          启停 App
 *  - pm list / grant / clear               包管理、权限
 *  - screencap / screenrecord              截图、录屏
 *  - settings put/get                       系统设置
 *  - svc / wm / cmd                         系统 service、窗口、命令
 *  - 任意 shell 命令                          adb 能做的它都能做
 *
 * 同时提供 [hasRoot] 检测与命令超时控制。
 */
object RootShellExecutor {

    /** 是否拥有 root 权限（缓存一次结果）。 */
    @Volatile private var rootChecked = false
    @Volatile var hasRoot = false
        private set

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    /** 检测 root 是否可用。主线程调用会阻塞，建议在协程里调用。 */
    fun checkRoot(): Boolean {
        if (rootChecked) return hasRoot
        rootChecked = true
        hasRoot = try {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val ok = p.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
            if (!ok) p.destroyForcibly()
            ok
        } catch (e: Throwable) {
            CommandLogManager.warn("root 检测失败: ${e.message}")
            false
        }
        CommandLogManager.info("Root 权限: $hasRoot")
        return hasRoot
    }

    data class Result(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0
    }

    /**
     * 以 root 执行单条命令。
     * @param cmd shell 命令
     * @param timeoutMs 超时
     */
    fun exec(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val start = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                CommandLogManager.warn("命令超时(${timeoutMs}ms): $cmd")
                Result(-1, out, "timeout")
            } else {
                val r = Result(p.exitValue(), out.trim(), err.trim())
                val cost = System.currentTimeMillis() - start
                CommandLogManager.log("ROOT", "[$cost ms] $cmd -> ${r.exit}")
                r
            }
        } catch (e: Throwable) {
            CommandLogManager.err("执行失败: $cmd | ${e.message}")
            Result(-2, "", e.message ?: "error")
        }
    }

    /** 以 root 执行多条命令（同一 su 会话，更快更可靠）。 */
    fun execBatch(cmds: List<String>, timeoutMs: Long = 20_000L): Result {
        val script = TextUtils.join("\n", cmds)
        return exec(script, timeoutMs)
    }

    // ===== 高级封装：系统级操控 =====

    /** 点击坐标。 */
    fun tap(x: Float, y: Float): Result = exec("input tap ${x.toInt()} ${y.toInt()}")

    /** 滑动。direction: up/down/left/right 或自定义起止点。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Result =
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")

    fun swipeDirection(direction: String, amount: Int = 500): Result {
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> intArrayOf(540, 1800, 540, 1800 - amount)
            "down" -> intArrayOf(540, 400, 540, 400 + amount)
            "left" -> intArrayOf(900, 1000, 900 - amount, 1000)
            "right" -> intArrayOf(180, 1000, 180 + amount, 1000)
            else -> intArrayOf(540, 1200, 540, 1200 - amount)
        }
        return swipe(x1, y1, x2, y2)
    }

    /** 输入文本（仅 ASCII；中文需用无障碍粘贴，见 AccessibilityExecutor）。 */
    fun inputText(text: String): Result =
        exec("input text \"${text.replace("\"", "\\\"").replace(" ", "%s")}\"")

    /** 按键。code 见 KeyEvent，常用：4=返回, 3=Home, 24/25=音量, 187=最近任务。 */
    fun keyEvent(code: Int): Result = exec("input keyevent $code")

    fun back() = keyEvent(4)
    fun home() = keyEvent(3)
    fun recents() = keyEvent(187)
    fun powerDialog() = keyEvent(26)
    fun volumeUp() = keyEvent(24)
    fun volumeDown() = keyEvent(25)

    /** 长按坐标。 */
    fun longPress(x: Float, y: Float, durationMs: Int = 800): Result =
        swipe(x.toInt(), y.toInt(), x.toInt(), y.toInt(), durationMs)

    /** 启动 App（按包名或 Activity）。 */
    fun launchApp(pkgOrComponent: String): Result =
        exec("am start -n $pkgOrComponent 2>/dev/null || monkey -p $pkgOrComponent -c android.intent.category.LAUNCHER 1")

    /** 按 URL/Intent 启动。 */
    fun launchUrl(url: String): Result = exec("am start -a android.intent.action.VIEW -d \"$url\"")

    /** 强制停止 App。 */
    fun forceStop(pkg: String): Result = exec("am force-stop $pkg")

    /** 清除 App 数据。 */
    fun clearAppData(pkg: String): Result = exec("pm clear $pkg")

    /** 列出已安装包名。 */
    fun listPackages(): List<String> {
        val r = exec("pm list packages")
        return if (r.ok) r.out.lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toList()
        else emptyList()
    }

    /** 截图保存到文件（PNG）。 */
    fun screenshot(targetFile: File): Boolean {
        val r = exec("screencap -p ${targetFile.absolutePath}")
        return r.ok && targetFile.exists() && targetFile.length() > 0
    }

    /** 设置系统设置项。namespace: system/global/secure。 */
    fun putSetting(namespace: String, key: String, value: String): Result =
        exec("settings put $namespace $key \"$value\"")

    fun getSetting(namespace: String, key: String): String =
        exec("settings get $namespace $key").out

    /** 授予权限。 */
    fun grantPermission(pkg: String, perm: String): Result = exec("pm grant $pkg $perm")

    /** 唤醒/解锁屏幕。 */
    fun wakeUp(): Result = exec("input keyevent 224")
    fun unlock(): Result = exec("input keyevent 82")
}
