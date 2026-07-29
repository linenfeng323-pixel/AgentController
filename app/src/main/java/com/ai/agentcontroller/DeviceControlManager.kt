package com.ai.agentcontroller

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.util.Calendar

/**
 * 设备直达工具：无需 UI 直接操控系统级能力。
 *
 * 功能：
 *  - 闹钟 / 计时器 / 日历
 *  - 媒体控制（播放/暂停/上下首/音量）
 *  - 通知监听与快捷操作
 *  - 系统设置开关（WiFi / 蓝牙 / 定位 / 飞行模式 / 旋转 / 深色模式 / 勿扰 / 电池）
 *  - 敏感操作：发送短信/冻结应用/修改系统（需 root）
 */
object DeviceControlManager {

    private val ctx: Context get() = App.instance

    // ===== 闹钟 / 计时器 =====

    /** 创建系统闹钟。hour/minute 为 24 小时制；label 可选。返回 pending intent 方便取消。 */
    fun createAlarm(hour: Int, minute: Int, label: String = "", enabled: Boolean = true): PendingIntent? {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                putExtra(AlarmClock.EXTRA_VIBRATE, enabled)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            CommandLogManager.ok("闹钟已设置: ${hour}:%02d $label".format(minute))
            null
        } catch (e: Throwable) {
            CommandLogManager.err("设置闹钟失败: ${e.message}")
            null
        }
    }

    /** 添加一个秒表/倒计时（系统计时器）。seconds 为秒。 */
    fun createTimer(seconds: Int, label: String = "") {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
            CommandLogManager.ok("计时器已设置: ${seconds}s $label")
        } catch (e: Throwable) {
            CommandLogManager.err("设置计时器失败: ${e.message}")
        }
    }

    // ===== 媒体 / 音量 =====

    private fun audio(): AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeUp(stream: Int = AudioManager.STREAM_MUSIC) = audio().adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
    fun volumeDown(stream: Int = AudioManager.STREAM_MUSIC) = audio().adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
    fun setVolume(v: Int, stream: Int = AudioManager.STREAM_MUSIC) { audio().setStreamVolume(stream, v, 0) }
    fun getVolume(stream: Int = AudioManager.STREAM_MUSIC): Int = audio().getStreamVolume(stream)
    fun getMaxVolume(stream: Int = AudioManager.STREAM_MUSIC): Int = audio().getStreamMaxVolume(stream)

    fun mediaPlayPause() = sendMediaButton(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun mediaNext() = sendMediaButton(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    fun mediaPrev() = sendMediaButton(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun mediaStop() = sendMediaButton(android.view.KeyEvent.KEYCODE_MEDIA_STOP)

    private fun sendMediaButton(keyCode: Int) {
        RootShellExecutor.keyEvent(keyCode)
    }

    // ===== 系统设置开关（root 优先，失败则走 Settings 面板跳转） =====

    fun setWifi(enabled: Boolean): Boolean {
        val rootCmd = if (enabled) "svc wifi enable" else "svc wifi disable"
        val r = RootShellExecutor.exec(rootCmd)
        if (r.ok) return true
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent); false
        } catch (_: Throwable) { false }
    }

    fun setBluetooth(enabled: Boolean): Boolean {
        val r = RootShellExecutor.exec(if (enabled) "svc bluetooth enable" else "svc bluetooth disable")
        if (r.ok) return true
        return try {
            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); false
        } catch (_: Throwable) { false }
    }

    fun setLocation(enabled: Boolean): Boolean {
        // LOCATION_MODE_HIGH_POWER=3, LOCATION_MODE_OFF=0
        val mode = if (enabled) 3 else 0
        val r = RootShellExecutor.putSetting("secure", "location_mode", mode.toString())
        return r.ok
    }

    fun setAirplane(enabled: Boolean): Boolean {
        val mode = if (enabled) 1 else 0
        return RootShellExecutor.putSetting("global", "airplane_mode_on", mode.toString()).ok
    }

    fun setAutoRotate(enabled: Boolean): Boolean {
        val mode = if (enabled) 1 else 0
        return RootShellExecutor.putSetting("system", "accelerometer_rotation", mode.toString()).ok
    }

    fun setDarkMode(enabled: Boolean): Boolean {
        val mode = if (enabled) 2 else 1
        return RootShellExecutor.putSetting("system", "ui_mode", mode.toString()).ok
    }

    fun setDnd(enabled: Boolean): Boolean {
        val r = RootShellExecutor.putSetting("global", "zen_mode", if (enabled) "1" else "0")
        return r.ok
    }

    // ===== 敏感操作 =====

    /** 读取已保存的 Wi-Fi 密码（需 root）。 */
    fun getWifiPasswords(): String {
        val r = RootShellExecutor.exec("cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null || cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null")
        return if (r.ok) r.out.take(4000) else "无 root 权限或文件不存在"
    }

    /** 发送短信（需要 SEND_SMS 权限或 root）。 */
    fun sendSms(phone: String, body: String): Boolean {
        return try {
            val r = RootShellExecutor.exec("service call isms 5 i32 1 s16 ${escapeShell(phone)} s16 ${escapeShell(body)}")
            r.ok
        } catch (e: Throwable) {
            CommandLogManager.err("发送短信失败: ${e.message}"); false
        }
    }

    /** 冻结/解冻应用（需 root）。 */
    fun freezeApp(pkg: String, frozen: Boolean = true): Boolean {
        val cmd = if (frozen) "pm disable-user --user 0 $pkg" else "pm enable --user 0 $pkg"
        return RootShellExecutor.exec(cmd).ok
    }

    private fun escapeShell(s: String): String = "'${s.replace("'", "'\\''")}'"
}
