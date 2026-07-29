package com.ai.agentcontroller

import java.io.File

/**
 * 混合执行器：把 [AgentCommand] 翻译为具体动作。
 *
 * 执行优先级：root 系统级命令优先（更快、更稳、权限更高），
 * 无障碍服务作为兜底与补充（中文输入、按文本找节点点击）。
 *
 * 支持的动作见 [AgentCommand.action]，新增动作只需在此处加 case。
 */
object HybridExecutor {

    /** 执行单条指令，返回是否成功。 */
    fun execute(cmd: AgentCommand): Boolean {
        CommandLogManager.log("EXEC", describe(cmd))
        return try {
            when (cmd.action) {
                "tap", "click_xy" -> AccessibilityExecutor.tapScreen(cmd.x, cmd.y)
                "long_press" -> AccessibilityExecutor.longPressScreen(cmd.x, cmd.y)
                "click" -> {
                    // target 可能是文本、id 或“x,y”
                    val t = cmd.target ?: return false
                    if (t.contains(",")) {
                        val (x, y) = t.split(",").map { it.trim().toFloat() }
                        AccessibilityExecutor.tapScreen(x, y)
                    } else {
                        AccessibilityExecutor.clickText(t)
                    }
                }
                "input_text" -> AccessibilityExecutor.inputText(cmd.text ?: "", cmd.target)
                "swipe" -> {
                    val dir = cmd.direction ?: "up"
                    if (cmd.x != 0f || cmd.y != 0f) {
                        AccessibilityExecutor.swipe(cmd.x, 1000f, cmd.x, 1000f - cmd.amount, 300)
                    } else {
                        AccessibilityExecutor.swipeDirection(dir, cmd.amount)
                    }
                }
                "scroll" -> AccessibilityExecutor.swipeDirection(cmd.direction ?: "down", if (cmd.amount > 0) cmd.amount else 500)
                "back" -> AccessibilityExecutor.back()
                "home" -> AccessibilityExecutor.home()
                "recents", "recent_apps" -> AccessibilityExecutor.recents()
                "wait", "sleep" -> { Thread.sleep(cmd.ms.coerceIn(0, 10000)); true }
                "open_app", "launch_app" -> AppLauncher.launch(cmd.pkg ?: cmd.target ?: "")
                "open_url" -> RootShellExecutor.launchUrl(cmd.target ?: "").ok
                "force_stop" -> RootShellExecutor.forceStop(cmd.pkg ?: cmd.target ?: "").ok
                "clear_data" -> RootShellExecutor.clearAppData(cmd.pkg ?: cmd.target ?: "").ok
                "keyevent", "key" -> RootShellExecutor.keyEvent(cmd.amount).ok
                "volume_up" -> RootShellExecutor.volumeUp().ok
                "volume_down" -> RootShellExecutor.volumeDown().ok
                "volume_set" -> { DeviceControlManager.setVolume(cmd.amount); true }
                "media_play" -> { DeviceControlManager.mediaPlayPause(); true }
                "media_next" -> { DeviceControlManager.mediaNext(); true }
                "media_prev" -> { DeviceControlManager.mediaPrev(); true }
                "media_stop" -> { DeviceControlManager.mediaStop(); true }
                "set_wifi" -> { DeviceControlManager.setWifi(cmd.target?.toBoolean() ?: true) }
                "set_bt" -> { DeviceControlManager.setBluetooth(cmd.target?.toBoolean() ?: true) }
                "set_location" -> { DeviceControlManager.setLocation(cmd.target?.toBoolean() ?: true) }
                "set_airplane" -> { DeviceControlManager.setAirplane(cmd.target?.toBoolean() ?: true) }
                "set_rotate" -> { DeviceControlManager.setAutoRotate(cmd.target?.toBoolean() ?: true) }
                "set_dark" -> { DeviceControlManager.setDarkMode(cmd.target?.toBoolean() ?: true) }
                "set_dnd" -> { DeviceControlManager.setDnd(cmd.target?.toBoolean() ?: true) }
                "freeze_app" -> { DeviceControlManager.freezeApp(cmd.pkg ?: cmd.target ?: "", cmd.target != "unfreeze") }
                "unfreeze_app" -> { DeviceControlManager.freezeApp(cmd.pkg ?: cmd.target ?: "", frozen = false) }
                "send_sms" -> {
                    val parts = (cmd.target ?: "").split(" ", limit = 2)
                    if (parts.size == 2) DeviceControlManager.sendSms(parts[0], parts[1]) else false
                }
                "wifi_passwords" -> { CommandLogManager.info(DeviceControlManager.getWifiPasswords()); true }
                "create_alarm" -> {
                    val r = Regex("(\\d{1,2}):(\\d{2})").find(cmd.target ?: "")
                    if (r != null) {
                        val h = r.groupValues[1].toInt(); val m = r.groupValues[2].toInt()
                        DeviceControlManager.createAlarm(h, m, cmd.text ?: ""); true
                    } else false
                }
                "create_timer" -> {
                    val s = Regex("(\\d+)").find(cmd.target ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 60
                    DeviceControlManager.createTimer(s, cmd.text ?: ""); true
                }
                "screenshot" -> {
                    val f = File(App.instance.cacheDir, "shot_${System.currentTimeMillis()}.png")
                    val ok = RootShellExecutor.screenshot(f)
                    if (ok) CommandLogManager.ok("截图: ${f.absolutePath}")
                    ok
                }
                "shell" -> RootShellExecutor.exec(cmd.target ?: "").ok
                "settings_put" -> {
                    val parts = (cmd.target ?: "").split(" ", limit = 3)
                    if (parts.size == 3) RootShellExecutor.putSetting(parts[0], parts[1], parts[2]).ok else false
                }
                "observe", "dump_screen" -> {
                    val text = AccessibilityExecutor.dumpScreenText()
                    CommandLogManager.info("屏幕内容:\n$text".take(2000))
                    true
                }
                "notify" -> { CommandLogManager.info("AI 提示: ${cmd.target}"); true }
                else -> { CommandLogManager.warn("未知动作: ${cmd.action}"); false }
            }.also { ok ->
                if (ok) CommandLogManager.ok("完成: ${cmd.action}") else CommandLogManager.warn("失败: ${cmd.action}")
            }
        } catch (e: Throwable) {
            CommandLogManager.err("异常: ${cmd.action} | ${e.message}")
            false
        }
    }

    /** 顺序执行一批指令，返回每条的成功情况。 */
    fun executeAll(batch: List<AgentCommand>): List<Boolean> = batch.map { execute(it) }

    fun describe(cmd: AgentCommand): String = buildString {
        append(cmd.action)
        cmd.target?.let { append(" target=$it") }
        cmd.text?.let { append(" text=$it") }
        cmd.direction?.let { append(" dir=$it") }
        if (cmd.amount != 0) append(" amount=${cmd.amount}")
        if (cmd.x != 0f || cmd.y != 0f) append(" (${cmd.x},${cmd.y})")
        if (cmd.ms != 300L && cmd.action in listOf("wait", "sleep")) append(" ms=${cmd.ms}")
        cmd.pkg?.let { append(" pkg=$it") }
    }
}
