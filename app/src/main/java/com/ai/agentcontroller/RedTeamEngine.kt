package com.ai.agentcontroller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 红队核心引擎：逆向 / 渗透 / 漏洞分析 / 内存取证 / 网络侦察。
 *
 * 所有操作通过 root shell 执行，结果结构化返回给 AI 执行者。
 * 工具分类：
 *  - APK 逆向：反编译 / 权限分析 / Smali 读取 / 字符串提取 / 组件安全检查
 *  - 网络侦察：端口扫描 / 主机发现 / WiFi 信息 / 抓包 / DNS 解析
 *  - 内存分析：进程枚举 / 内存搜索 / 内存转储 / 字符串提取
 *  - 二进制分析：ELF 解析 / DEX 分析 / 字符串 / 符号表
 *  - 漏洞检测：CVE 查询 / 权限提升检测 / 安全配置审计
 *  - 利用框架：Shell 执行 / 文件操作 / 注入检测
 */
object RedTeamEngine {

    private val ctx get() = App.instance

    data class ToolResult(val ok: Boolean, val text: String, val data: JSONObject = JSONObject()) {
        fun toMcp(): JSONObject = JSONObject().apply {
            put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            if (!ok) put("isError", true)
            if (data.length() > 0) put("data", data)
        }
    }

    // ===== APK 逆向 =====

    /** 反编译 APK：解压 + 提取 manifest + dex + resources */
    fun decompileApk(path: String): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult(false, "文件不存在: $path")
        val outDir = File(ctx.cacheDir, "apk_${System.currentTimeMillis()}")
        outDir.mkdirs()
        // 用 root 解压
        val r = RootShellExecutor.exec("unzip -o '$path' -d '${outDir.absolutePath}' 2>&1")
        if (!r.ok) return ToolResult(false, "解压失败: ${r.err}")
        // 读取 AndroidManifest.xml（二进制，用 aapt 解析）
        val manifest = RootShellExecutor.exec("aapt dump permissions '$path' 2>/dev/null || aapt2 dump permissions '$path' 2>/dev/null")
        val badging = RootShellExecutor.exec("aapt dump badging '$path' 2>/dev/null || aapt2 dump packagename '$path' 2>/dev/null")
        // 列出 dex 文件
        val dexFiles = RootShellExecutor.exec("find '${outDir.absolutePath}' -name '*.dex' 2>/dev/null")
        // 提取签名信息
        val sigInfo = RootShellExecutor.exec("keytool -printcert -jarfile '$path' 2>/dev/null | head -20")
        val data = JSONObject().apply {
            put("outputDir", outDir.absolutePath)
            put("permissions", manifest.out)
            put("badging", badging.out)
            put("dexFiles", dexFiles.out)
            put("signature", sigInfo.out)
        }
        return ToolResult(true, buildString {
            appendLine("=== APK 反编译完成 ===")
            appendLine("输出目录: ${outDir.absolutePath}")
            appendLine("权限列表:\n${manifest.out}")
            appendLine("应用信息:\n${badging.out.take(500)}")
            appendLine("DEX 文件:\n${dexFiles.out}")
            if (sigInfo.out.isNotBlank()) appendLine("签名:\n${sigInfo.out}")
        }, data)
    }

    /** 分析 AndroidManifest：导出组件 + 权限风险 */
    fun analyzeManifest(apkPath: String): ToolResult {
        val r = RootShellExecutor.exec("aapt dump xmltree '$apkPath' AndroidManifest.xml 2>/dev/null")
        if (!r.ok || r.out.isBlank()) {
            // 备用：直接从解压目录读
            val r2 = RootShellExecutor.exec("aapt2 dump xmltree '$apkPath' --file AndroidManifest.xml 2>/dev/null")
            return parseManifest(r2.out, apkPath)
        }
        return parseManifest(r.out, apkPath)
    }

    private fun parseManifest(xml: String, apkPath: String): ToolResult {
        val exportedComponents = mutableListOf<String>()
        val dangerousPerms = mutableListOf<String>()
        // 解析导出组件
        Regex("""(activity|service|receiver|provider)[\s\S]*?android:exported="true"[\s\S]*?android:name="([^"]+)"""").findAll(xml).forEach { m ->
            exportedComponents.add("${m.groupValues[1]}: ${m.groupValues[2]}")
        }
        // 危险权限
        val dangerous = setOf("READ_SMS","SEND_SMS","READ_CONTACTS","WRITE_CONTACTS","ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION","RECORD_AUDIO","CAMERA","READ_CALL_LOG","WRITE_CALL_LOG",
            "READ_PHONE_STATE","READ_EXTERNAL_STORAGE","WRITE_EXTERNAL_STORAGE","SYSTEM_ALERT_WINDOW",
            "REQUEST_INSTALL_PACKAGES","DRAW_OVER_OTHER_APPS","READ_PHONE_NUMBERS","USE_SIP","PROCESS_OUTGOING_CALLS")
        Regex("""android:name="android\.permission\.(\w+)"""").findAll(xml).forEach { m ->
            if (dangerous.contains(m.groupValues[1])) dangerousPerms.add(m.groupValues[1])
        }
        val data = JSONObject().apply {
            put("exportedComponents", JSONArray(exportedComponents))
            put("dangerousPermissions", JSONArray(dangerousPerms))
            put("riskLevel", if (exportedComponents.size > 3 || dangerousPerms.size > 5) "HIGH" else if (exportedComponents.isNotEmpty()) "MEDIUM" else "LOW")
        }
        return ToolResult(true, buildString {
            appendLine("=== Manifest 安全分析 ===")
            appendLine("导出组件 (${exportedComponents.size}):")
            exportedComponents.forEach { appendLine("  [!] $it") }
            appendLine("危险权限 (${dangerousPerms.size}):")
            dangerousPerms.forEach { appendLine("  [!] $it") }
            appendLine("风险等级: ${data.optString("riskLevel")}")
        }, data)
    }

    /** 提取 APK 中的字符串：URL / IP / 密钥 / Token */
    fun extractStrings(apkPath: String): ToolResult {
        val r = RootShellExecutor.exec("unzip -p '$apkPath' '*.dex' 2>/dev/null | strings -n 6 | grep -E " +
                "'(https?://|[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+|api[_-]?key|secret|token|password|Bearer|AKIA|JWT)' 2>/dev/null | sort -u | head -100")
        val urls = Regex("""https?://[^\s"'<>]+""").findAll(r.out).map { it.value }.toSet()
        val ips = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").findAll(r.out).map { it.value }.toSet()
        val secrets = Regex("""(?i)(api[_-]?key|secret|token|password|bearer)[\s:=]+[A-Za-z0-9_\-/+=]{8,}""").findAll(r.out).map { it.value }.toSet()
        val data = JSONObject().apply {
            put("urls", JSONArray(urls))
            put("ips", JSONArray(ips))
            put("secrets", JSONArray(secrets))
        }
        return ToolResult(true, buildString {
            appendLine("=== 字符串提取 ===")
            appendLine("URL (${urls.size}):")
            urls.take(30).forEach { appendLine("  $it") }
            appendLine("IP 地址 (${ips.size}):")
            ips.forEach { appendLine("  $it") }
            appendLine("疑似密钥 (${secrets.size}):")
            secrets.take(20).forEach { appendLine("  [!] $it") }
        }, data)
    }

    // ===== 网络侦察 =====

    /** 端口扫描（用 shell 实现，无需 nmap） */
    fun portScan(target: String, ports: String = "1-1000"): ToolResult {
        val portList = if (ports.contains("-")) {
            val (s, e) = ports.split("-")
            (s.toInt()..e.toInt()).toList()
        } else ports.split(",").map { it.trim().toInt() }
        val openPorts = mutableListOf<Int>()
        // 并发探测
        val cmds = portList.map { p ->
            "echo >/dev/tcp/$target/$p 2>/dev/null && echo OPEN:$p || true"
        }
        val r = RootShellExecutor.execBatch(cmds.take(200), 30_000L)
        Regex("""OPEN:(\d+)""").findAll(r.out).forEach { openPorts.add(it.groupValues[1].toInt()) }
        val data = JSONObject().put("openPorts", JSONArray(openPorts)).put("target", target)
        return ToolResult(true, buildString {
            appendLine("=== 端口扫描 $target ===")
            appendLine("扫描范围: $ports")
            appendLine("开放端口 (${openPorts.size}):")
            openPorts.sorted().forEach { appendLine("  $it/tcp OPEN") }
            if (openPorts.isEmpty()) appendLine("  (未发现开放端口)")
        }, data)
    }

    /** 主机发现：扫描局域网存活主机 */
    fun hostDiscovery(subnet: String = "192.168.1"): ToolResult {
        val r = RootShellExecutor.exec(
            "for i in \$(seq 1 254); do ping -c1 -W1 $subnet.\$i 2>/dev/null | grep -q '1 received' && echo ALIVE:$subnet.\$i & done; wait"
        )
        val hosts = Regex("""ALIVE:([\d.]+)""").findAll(r.out).map { it.groupValues[1] }.toList()
        val data = JSONObject().put("aliveHosts", JSONArray(hosts))
        return ToolResult(true, buildString {
            appendLine("=== 主机发现 $subnet.0/24 ===")
            appendLine("存活主机 (${hosts.size}):")
            hosts.forEach { appendLine("  $it ALIVE") }
        }, data)
    }

    /** WiFi 信息收集 */
    fun wifiInfo(): ToolResult {
        val r1 = RootShellExecutor.exec("ip addr show wlan0 2>/dev/null || ifconfig wlan0 2>/dev/null")
        val r2 = RootShellExecutor.exec("ip route 2>/dev/null || route -n 2>/dev/null")
        val r3 = RootShellExecutor.exec("cat /proc/net/wireless 2>/dev/null")
        val r4 = RootShellExecutor.exec("settings get secure wifi_ssid 2>/dev/null; dumpsys wifi 2>/dev/null | grep -E 'SSID|signal|mWifiInfo' | head -5")
        val data = JSONObject().apply {
            put("interface", r1.out)
            put("routes", r2.out)
            put("wireless", r3.out)
            put("wifi", r4.out)
        }
        return ToolResult(true, buildString {
            appendLine("=== WiFi 信息 ===")
            appendLine("接口:\n${r1.out.take(300)}")
            appendLine("路由:\n${r2.out.take(300)}")
            appendLine("信号:\n${r3.out}")
            appendLine("SSID:\n${r4.out.take(300)}")
        }, data)
    }

    /** DNS 解析 */
    fun dnsResolve(domain: String): ToolResult {
        val r = RootShellExecutor.exec("nslookup $domain 2>/dev/null || getent hosts $domain 2>/dev/null || ping -c1 -W2 $domain 2>/dev/null | head -1")
        val ips = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").findAll(r.out).map { it.value }.toSet()
        val data = JSONObject().put("domain", domain).put("ips", JSONArray(ips))
        return ToolResult(true, buildString {
            appendLine("=== DNS 解析 $domain ===")
            ips.forEach { appendLine("  $it") }
            if (ips.isEmpty()) appendLine("  解析失败")
            appendLine("\n原始输出:\n${r.out.take(500)}")
        }, data)
    }

    // ===== 内存分析 =====

    /** 进程枚举（含 PID/UID/内存占用） */
    fun listProcesses(filter: String = ""): ToolResult {
        val cmd = if (filter.isNotBlank()) "ps -A 2>/dev/null | grep -i '$filter'" else "ps -A 2>/dev/null"
        val r = RootShellExecutor.exec(cmd)
        return ToolResult(true, "=== 进程列表 ===\n${r.out.take(8000)}")
    }

    /** 内存搜索（类似 GameGuardian：在指定进程内存中搜索值） */
    fun searchMemory(pid: Int, pattern: String): ToolResult {
        // 读取 /proc/pid/maps 找可读内存段，用 grep 搜索
        val maps = RootShellExecutor.exec("cat /proc/$pid/maps 2>/dev/null | grep -E 'r..p' | head -50")
        if (maps.out.isBlank()) return ToolResult(false, "无法读取进程 $pid 的内存映射")
        // 在每个可读段中搜索
        val results = mutableListOf<String>()
        Regex("""([0-9a-f]+)-([0-9a-f]+)\s+(r..p)\s+([0-9a-f]+)\s+\S+\s+\S+\s*(.*)""").findAll(maps.out).take(30).forEach { m ->
            val start = m.groupValues[1].toLong(16)
            val end = m.groupValues[2].toLong(16)
            val perms = m.groupValues[3]
            val label = m.groupValues[5].trim()
            val size = end - start
            if (size > 0 && size < 10_000_000) {
                val sr = RootShellExecutor.exec("dd if=/proc/$pid/mem bs=1 skip=$start count=$size 2>/dev/null | strings -n 4 | grep -i '$pattern' | head -10")
                if (sr.out.isNotBlank()) {
                    results.add("[$perms] 0x${start.toString(16)}-0x${end.toString(16)} ($label):\n${sr.out}")
                }
            }
        }
        return ToolResult(true, buildString {
            appendLine("=== 内存搜索 PID=$pid pattern='$pattern' ===")
            appendLine("扫描了 ${maps.out.lines().size} 个内存段")
            appendLine("命中 ${results.size} 段:")
            results.take(20).forEach { appendLine(it) }
        })
    }

    /** 内存转储：dump 指定进程的完整内存 */
    fun dumpProcessMemory(pid: Int, maxMb: Int = 50): ToolResult {
        val outFile = File(ctx.cacheDir, "memdump_${pid}_${System.currentTimeMillis()}.bin")
        val r = RootShellExecutor.exec("cat /proc/$pid/maps 2>/dev/null | grep 'r..p' | " +
                "awk '{split(\$1,a,\"-\"); print a[1], a[2]}' | " +
                "while read s e; do " +
                "dd if=/proc/$pid/mem bs=4096 skip=\$((16#\$s/4096)) count=\$(((16#\$e-16#\$s)/4096)) 2>/dev/null; " +
                "done | head -c ${maxMb * 1024 * 1024} > '${outFile.absolutePath}'")
        val size = if (outFile.exists()) outFile.length() else 0
        // 提取可读字符串
        val strings = RootShellExecutor.exec("strings -n 6 '${outFile.absolutePath}' 2>/dev/null | grep -E " +
                "'(http|key|pass|token|secret|flag|[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})' | sort -u | head -50")
        return ToolResult(true, buildString {
            appendLine("=== 内存转储 PID=$pid ===")
            appendLine("转储文件: ${outFile.absolutePath}")
            appendLine("大小: ${size / 1024} KB")
            appendLine("敏感字符串:")
            appendLine(strings.out)
        })
    }

    // ===== 二进制分析 =====

    /** ELF 二进制分析：头部 / 段 / 符号表 / 字符串 */
    fun analyzeElf(path: String): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult(false, "文件不存在: $path")
        val header = RootShellExecutor.exec("readelf -h '$path' 2>/dev/null || file '$path'")
        val sections = RootShellExecutor.exec("readelf -S '$path' 2>/dev/null | head -40")
        val symbols = RootShellExecutor.exec("readelf -s '$path' 2>/dev/null | grep -E 'FUNC|OBJECT' | head -30")
        val strings = RootShellExecutor.exec("strings -n 8 '$path' 2>/dev/null | grep -E " +
                "'(http|/bin/|/system/|dlopen|mmap|exec|open|read|write|connect|socket)' | sort -u | head -40")
        val data = JSONObject().apply {
            put("header", header.out)
            put("sections", sections.out)
            put("symbols", symbols.out)
            put("strings", strings.out)
        }
        return ToolResult(true, buildString {
            appendLine("=== ELF 分析 $path ===")
            appendLine("头部:\n${header.out.take(300)}")
            appendLine("段:\n${sections.out.take(500)}")
            appendLine("符号:\n${symbols.out.take(500)}")
            appendLine("关键字符串:\n${strings.out}")
        }, data)
    }

    /** DEX 文件分析 */
    fun analyzeDex(path: String): ToolResult {
        val f = File(path)
        if (!f.exists()) return ToolResult(false, "文件不存在: $path")
        val header = RootShellExecutor.exec("xxd -l 112 '$path' 2>/dev/null")
        val classes = RootShellExecutor.exec("strings '$path' 2>/dev/null | grep -E '^L[a-z]' | sort -u | head -50")
        val methods = RootShellExecutor.exec("strings '$path' 2>/dev/null | grep -E '^(onCreate|onStart|onResume|onClick|doInBackground|<init>|<clinit>)' | sort -u | head -30")
        val strings = RootShellExecutor.exec("strings -n 8 '$path' 2>/dev/null | grep -E " +
                "'(http|api|key|secret|token|password|Ljavax|Ljava|Landroid|Lcom)' | sort -u | head -50")
        return ToolResult(true, buildString {
            appendLine("=== DEX 分析 $path ===")
            appendLine("头部:\n${header.out}")
            appendLine("类:\n${classes.out}")
            appendLine("方法:\n${methods.out}")
            appendLine("字符串:\n${strings.out}")
        })
    }

    // ===== 漏洞检测 =====

    /** 检查 Root 检测绕过：哪些路径可写 */
    fun checkPrivEsc(): ToolResult {
        val checks = mutableListOf<Pair<String, Boolean>>()
        // su 可用
        checks.add("su 可用" to RootShellExecutor.checkRoot())
        // 可写目录
        val writable = RootShellExecutor.exec("find /system /data/local /sdcard -maxdepth 2 -writable -type d 2>/dev/null | head -20")
        // SELinux 状态
        val selinux = RootShellExecutor.exec("getenforce 2>/dev/null; sestatus 2>/dev/null")
        // 内核版本
        val kernel = RootShellExecutor.exec("uname -a; cat /proc/version")
        // SUID 文件
        val suid = RootShellExecutor.exec("find / -perm -4000 -type f 2>/dev/null | head -20")
        // 可写系统属性
        val props = RootShellExecutor.exec("getprop | grep -E 'ro.debuggable|ro.secure|ro.build.type|service.adb.root' 2>/dev/null")
        return ToolResult(true, buildString {
            appendLine("=== 权限提升检测 ===")
            appendLine("Root: ${if (checks[0].second) "✅ 已获取" else "❌ 未获取"}")
            appendLine("SELinux: $selinux")
            appendLine("内核: ${kernel.out.take(200)}")
            appendLine("SUID 文件:\n${suid.out}")
            appendLine("系统属性:\n${props.out}")
            appendLine("可写目录:\n${writable.out}")
        })
    }

    /** 安全配置审计 */
    fun securityAudit(): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("=== 安全配置审计 ===")
        // 检查 debuggable
        val dbg = RootShellExecutor.exec("getprop ro.debuggable 2>/dev/null")
        sb.appendLine("Debuggable: ${dbg.out.trim()} ${if (dbg.out.trim() == "1") "[!] 可调试" else "✓"}")
        // 检查 ADB
        val adb = RootShellExecutor.exec("settings get global adb_enabled 2>/dev/null")
        sb.appendLine("ADB: ${adb.out.trim()}")
        // 检查未知来源
        val unk = RootShellExecutor.exec("settings get secure install_non_market_apps 2>/dev/null")
        sb.appendLine("未知来源: ${unk.out.trim()}")
        // 检查加密
        val enc = RootShellExecutor.exec("getprop ro.crypto.state 2>/dev/null")
        sb.appendLine("磁盘加密: ${enc.out.trim()}")
        // 检查锁屏
        val lock = RootShellExecutor.exec("locksettings get-disabled 2>/dev/null; dumpsys trust 2>/dev/null | grep -i 'trusted' | head -5")
        sb.appendLine("锁屏状态: ${lock.out.trim()}")
        // 已安装应用安全检查
        val pkgs = RootShellExecutor.exec("pm list packages -3 2>/dev/null | head -30")
        sb.appendLine("第三方应用:\n${pkgs.out}")
        return ToolResult(true, sb.toString())
    }

    // ===== 抓包辅助 =====

    /** 启动 tcpdump 抓包（需要 root + tcpdump 已安装） */
    fun startCapture(interfaceName: String = "wlan0", durationSec: Int = 60, filter: String = ""): ToolResult {
        val outFile = File(ctx.cacheDir, "capture_${System.currentTimeMillis()}.pcap")
        val cmd = if (filter.isNotBlank())
            "timeout ${durationSec} tcpdump -i $interfaceName -w '${outFile.absolutePath}' '$filter' 2>&1"
        else
            "timeout ${durationSec} tcpdump -i $interfaceName -w '${outFile.absolutePath}' 2>&1"
        val r = RootShellExecutor.exec(cmd, (durationSec + 10) * 1000L)
        val size = if (outFile.exists()) outFile.length() else 0
        // 快速分析
        val analysis = if (size > 0) RootShellExecutor.exec("tcpdump -r '${outFile.absolutePath}' -n 2>/dev/null | head -50").out else ""
        return ToolResult(true, buildString {
            appendLine("=== 抓包完成 ===")
            appendLine("文件: ${outFile.absolutePath}")
            appendLine("大小: ${size / 1024} KB")
            appendLine("前 50 个包:\n$analysis")
        })
    }

    // ===== Shell 执行（红队模式） =====

    /** 执行任意 root shell 命令 */
    fun execShell(cmd: String, root: Boolean = true): ToolResult {
        val r = if (root) RootShellExecutor.exec(cmd) else RootShellExecutor.exec(cmd)
        return ToolResult(r.ok, buildString {
            appendLine("=== Shell 执行 ===")
            appendLine("命令: $cmd")
            appendLine("退出码: ${r.exit}")
            appendLine("输出:\n${r.out.take(5000)}")
            if (r.err.isNotBlank()) appendLine("错误:\n${r.err.take(2000)}")
        })
    }

    /** 文件读取（root，支持任意路径） */
    fun readFile(path: String): ToolResult {
        val r = RootShellExecutor.exec("cat '$path' 2>&1")
        return ToolResult(r.ok, r.out.take(10000))
    }

    /** 文件搜索 */
    fun searchFiles(path: String, pattern: String): ToolResult {
        val r = RootShellExecutor.exec("find '$path' -name '$pattern' -type f 2>/dev/null | head -100")
        return ToolResult(true, "=== 文件搜索 ===\n路径: $path\n模式: $pattern\n结果:\n${r.out}")
    }

    // ===== 工具注册表（供 AI 调用） =====

    data class RedTeamTool(val name: String, val desc: String, val args: Map<String, String>)

    val tools: List<RedTeamTool> = listOf(
        // APK 逆向
        RedTeamTool("apk_decompile", "反编译 APK：解压+提取manifest+dex+签名", mapOf("path" to "string")),
        RedTeamTool("apk_manifest", "分析 AndroidManifest：导出组件+危险权限", mapOf("path" to "string")),
        RedTeamTool("apk_strings", "提取 APK 中的 URL/IP/密钥/Token", mapOf("path" to "string")),
        // 网络侦察
        RedTeamTool("net_scan", "端口扫描", mapOf("target" to "string", "ports" to "string")),
        RedTeamTool("net_hosts", "局域网主机发现", mapOf("subnet" to "string")),
        RedTeamTool("net_wifi", "WiFi 信息收集", mapOf()),
        RedTeamTool("net_dns", "DNS 解析", mapOf("domain" to "string")),
        RedTeamTool("net_capture", "抓包（tcpdump）", mapOf("interface" to "string", "duration" to "integer", "filter" to "string")),
        // 内存分析
        RedTeamTool("mem_procs", "枚举进程", mapOf("filter" to "string")),
        RedTeamTool("mem_search", "内存搜索（类似GG）", mapOf("pid" to "integer", "pattern" to "string")),
        RedTeamTool("mem_dump", "内存转储+敏感字符串提取", mapOf("pid" to "integer", "max_mb" to "integer")),
        // 二进制分析
        RedTeamTool("bin_elf", "ELF 二进制分析", mapOf("path" to "string")),
        RedTeamTool("bin_dex", "DEX 文件分析", mapOf("path" to "string")),
        // 漏洞检测
        RedTeamTool("vuln_privesc", "权限提升检测", mapOf()),
        RedTeamTool("vuln_audit", "安全配置审计", mapOf()),
        // Shell & 文件
        RedTeamTool("shell", "执行 root shell 命令", mapOf("command" to "string", "root" to "boolean")),
        RedTeamTool("file_read", "读取文件（任意路径）", mapOf("path" to "string")),
        RedTeamTool("file_search", "搜索文件", mapOf("path" to "string", "pattern" to "string"))
    )

    /** 执行红队工具 */
    fun callTool(name: String, args: JSONObject): ToolResult {
        return when (name) {
            "apk_decompile" -> decompileApk(args.optString("path"))
            "apk_manifest" -> analyzeManifest(args.optString("path"))
            "apk_strings" -> extractStrings(args.optString("path"))
            "net_scan" -> portScan(args.optString("target"), args.optString("ports", "1-1000"))
            "net_hosts" -> hostDiscovery(args.optString("subnet", "192.168.1"))
            "net_wifi" -> wifiInfo()
            "net_dns" -> dnsResolve(args.optString("domain"))
            "net_capture" -> startCapture(args.optString("interface", "wlan0"), args.optInt("duration", 60), args.optString("filter"))
            "mem_procs" -> listProcesses(args.optString("filter"))
            "mem_search" -> searchMemory(args.optInt("pid"), args.optString("pattern"))
            "mem_dump" -> dumpProcessMemory(args.optInt("pid"), args.optInt("max_mb", 50))
            "bin_elf" -> analyzeElf(args.optString("path"))
            "bin_dex" -> analyzeDex(args.optString("path"))
            "vuln_privesc" -> checkPrivEsc()
            "vuln_audit" -> securityAudit()
            "shell" -> execShell(args.optString("command"), args.optBoolean("root", true))
            "file_read" -> readFile(args.optString("path"))
            "file_search" -> searchFiles(args.optString("path"), args.optString("pattern"))
            else -> ToolResult(false, "未知工具: $name")
        }
    }
}
