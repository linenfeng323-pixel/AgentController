package com.ai.agentcontroller

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获与地址符号化。
 *
 * 借鉴白给 v19 项目里 Segfault 139 崩溃定位的需求：
 *  - 注册全局未捕获异常处理器，把 Java 崩溃写入文件
 *  - 读取系统 tombstone（root）最近条目
 *  - 对 native 崩溃地址做简单符号化：在 /proc/<pid>/maps 里反查所属库与偏移
 *
 * 输出位置：应用缓存目录 /sdcard/Download（root 时）
 */
object CrashReporter : Thread.UncaughtExceptionHandler {

    private val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        CommandLogManager.info("崩溃捕获已安装")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val report = buildJavaReport(t, e)
            val file = File(App.instance.cacheDir, "crash_${fmt.format(Date())}.log")
            file.writeText(report)
            CommandLogManager.err("Java 崩溃已记录: ${file.absolutePath}\n${e.stackTraceToString().take(1500)}")
        } catch (_: Throwable) {}
        previousHandler?.uncaughtException(t, e)
    }

    private fun buildJavaReport(t: Thread, e: Throwable): String = buildString {
        appendLine("====== Java 崩溃报告 ======")
        appendLine("时间: ${Date()}")
        appendLine("线程: ${t.name}")
        appendLine("异常: ${e::class.java.name}: ${e.message}")
        appendLine("堆栈:")
        appendLine(e.stackTraceToString())
    }

    /** 读取最近 native tombstone（需 root）。 */
    fun dumpRecentTombstones(limit: Int = 3): String {
        if (!RootShellExecutor.checkRoot()) return "无 root，无法读取 tombstone"
        val list = RootShellExecutor.exec("ls -t /data/tombstones/ 2>/dev/null | head -$limit").out
        if (list.isBlank()) return "无 tombstone"
        val sb = StringBuilder()
        list.lineSequence().filter { it.isNotBlank() }.forEach { name ->
            val path = "/data/tombstones/$name"
            val head = RootShellExecutor.exec("head -40 '$path' 2>/dev/null").out
            sb.appendLine("==== $path ====").appendLine(head).appendLine()
        }
        return sb.toString()
    }

    /**
     * 简单地址符号化：在指定进程的 /proc/<pid>/maps 中查找 [addr] 所属库与偏移。
     * @param pid 进程号
     * @param addr 十六进制地址如 0x7f12345678
     */
    fun symbolize(pid: Int, addr: Long): String {
        if (!RootShellExecutor.checkRoot()) return "无 root，无法符号化"
        val maps = RootShellExecutor.exec("cat /proc/$pid/maps 2>/dev/null").out
        if (maps.isBlank()) return "无法读取 /proc/$pid/maps"
        for (line in maps.lineSequence()) {
            val range = line.substringBefore(' ').split('-')
            if (range.size != 2) continue
            val start = range[0].toLongOrNull(16) ?: continue
            val end = range[1].toLongOrNull(16) ?: continue
            if (addr in start until end) {
                val lib = line.substringAfterLast(' ', "?")
                val offset = addr - start
                return "$lib + 0x${offset.toString(16)} (addr=0x${addr.toString(16)})"
            }
        }
        return "未在 maps 中找到 0x${addr.toString(16)}"
    }
}
