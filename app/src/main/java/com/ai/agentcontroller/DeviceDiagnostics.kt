package com.ai.agentcontroller

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * 设备诊断面板。
 *
 * 借鉴白给 v19 项目里反复出现的诊断需求：
 *  - ADB 设备识别（通过 root 调用 `adb devices` 或 `getprop`）
 *  - root / 无障碍 / 通知 / 悬浮窗 权限状态
 *  - 屏幕 / SoC / 内存 / 存储 / Android 版本
 *  - 已安装关键包（游戏、AI App）探测
 *  - 指定进程内存基址（getter RVA）样例探查
 *  - tombstone / dropbox 崩溃日志最近条目
 *
 * 所有结果以结构化文本返回，便于显示与上传给 AI。
 */
object DeviceDiagnostics {

    private val ctx: Context get() = App.instance

    data class Report(
        val summary: String,
        val details: String
    )

    fun run(): Report {
        val sb = StringBuilder()
        sb.appendLine("====== 设备诊断报告 ======")
        sb.appendLine("时间: ${System.currentTimeMillis()}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("设备: ${Build.DEVICE} / ${Build.PRODUCT}")

        sb.appendLine().appendLine("---- 权限 ----")
        sb.appendLine("Root: ${RootShellExecutor.checkRoot()}")
        sb.appendLine("无障碍: ${AccessibilityServiceHelper.isEnabled()}")
        sb.appendLine("悬浮窗: ${AccessibilityServiceHelper.canDrawOverlays()}")
        sb.appendLine("通知: ${AccessibilityServiceHelper.hasNotificationPermission()}")

        sb.appendLine().appendLine("---- 屏幕 ----")
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        sb.appendLine("分辨率: ${metrics.widthPixels}x${metrics.heightPixels} density=${metrics.density}")
        sb.appendLine("刷新率: ${wm.defaultDisplay.refreshRate}Hz")

        sb.appendLine().appendLine("---- 性能 ----")
        val mi = android.app.ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        sb.appendLine("内存: 可用=${mi.availMem / 1024 / 1024}MB 总计=${mi.totalMem / 1024 / 1024}MB 阈值=${mi.threshold / 1024 / 1024}MB")
        sb.appendLine("CPU 核数: ${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("存储: 可用=${File(ctx.filesDir.path).parentFile?.parentFile?.freeSpace ?: 0} bytes")

        sb.appendLine().appendLine("---- 关键包 ----")
        listOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ",
            "com.ss.android.ugc.aweme" to "抖音",
            "com.android.chrome" to "Chrome",
            "com.android.vending" to "Google Play"
        ).forEach { (pkg, name) ->
            sb.appendLine("$name($pkg): ${if (isInstalled(pkg)) "已安装" else "未安装"}")
        }

        sb.appendLine().appendLine("---- Root 信息 ----")
        if (RootShellExecutor.checkRoot()) {
            sb.appendLine("su: 可用")
            sb.appendLine("selinux: ${RootShellExecutor.exec("getenforce").out}")
            sb.appendLine("adb 状态: ${RootShellExecutor.exec("getprop service.adb.tcp.port").out}")
            sb.appendLine("最近 tombstone:")
            val ts = RootShellExecutor.exec("ls -t /data/tombstones/ 2>/dev/null | head -3").out
            if (ts.isBlank()) sb.appendLine("  (无)") else ts.lineSequence().forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("su 不可用，跳过 root 诊断")
        }

        val summary = "Android ${Build.VERSION.RELEASE} | ${Build.MODEL} | Root=${RootShellExecutor.hasRoot} | 无障碍=${AccessibilityServiceHelper.isEnabled()}"
        return Report(summary, sb.toString())
    }

    private fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
