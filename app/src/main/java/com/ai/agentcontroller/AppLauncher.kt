package com.ai.agentcontroller

import android.content.Intent
import android.content.pm.PackageManager

/**
 * 应用启动器：把“中文名 / 简称 / 包名”统一解析为真实包名再启动。
 *
 * 解决核心问题：[RootShellExecutor.launchApp] 直接把字符串当包名传给
 * `am start -n`，遇到“微信”“MT管理器”等中文名必然返回 252 失败。
 *
 * 解析顺序：
 *  1. 已是合法包名且已安装 → 直接用
 *  2. 内置别名表（忽略大小写、包含匹配）
 *  3. PackageManager 查所有有启动入口的应用，按应用名(label)匹配
 *     —— 精确 → 忽略大小写 → 包含
 */
object AppLauncher {

    /** 常见国产 App 别名：中文名 / 简称 → 包名。label 匹配兜底不到时使用。 */
    private val alias: Map<String, String> = mapOf(
        "微信" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "微博" to "com.sina.weibo",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "网易云音乐" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "知乎" to "com.zhihu.android",
        "小红书" to "com.xingin.xhs",
        "mt管理器" to "bin.mt.plus",
        "np管理器" to "com.np.manager",
        "termux" to "com.termux",
        "设置" to "com.android.settings",
        "相机" to "com.android.camera",
        "电话" to "com.android.dialer",
        "短信" to "com.android.mms",
        "信息" to "com.android.mms",
        "计算器" to "com.android.calculator2",
        "日历" to "com.android.calendar",
        "时钟" to "com.android.deskclock",
        "文件管理" to "com.android.filemanagement",
        "豆包" to "com.larus.nova",
        "deepseek" to "com.deepseek.ai",
        "kimi" to "com.moonshot.moonshot",
        "通义" to "com.alibaba.aliyunlingxi",
        "夸克" to "com.quark.browser",
        "uc浏览器" to "com.UCMobile",
        "qq浏览器" to "com.tencent.mtt",
        "百度" to "com.baidu.searchbox",
        "edge" to "com.microsoft.emmx",
        "chrome" to "com.android.chrome",
        "via" to "mark.via"
    )

    /** label → pkg 缓存（首次解析时构建）。 */
    @Volatile
    private var labelCache: Map<String, String>? = null

    /** 解析为真实包名。失败返回 null。 */
    fun resolvePackage(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null

        // 1. 已是合法包名格式（至少含一个点，仅小写字母/数字/下划线/点）
        if (n.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+"))) {
            if (isInstalled(n)) return n
        }

        // 2. 别名表：忽略大小写
        val key = n.lowercase()
        alias[key]?.let { if (isInstalled(it)) return it }
        // 别名包含匹配
        for ((k, v) in alias) {
            if (k.contains(key) || key.contains(k)) {
                if (isInstalled(v)) return v
            }
        }

        // 3. PackageManager label 匹配
        val cache = ensureLabelCache()
        // 精确
        cache[n]?.let { return it }
        // 忽略大小写精确
        cache.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.let { return it.value }
        // 包含
        cache.entries.firstOrNull {
            it.key.contains(n, ignoreCase = true) || n.contains(it.key, ignoreCase = true)
        }?.let { return it.value }

        return null
    }

    /** 启动应用：解析名称 → root 优先 am start，否则 getLaunchIntentForPackage。 */
    fun launch(name: String): Boolean {
        val pkg = resolvePackage(name) ?: run {
            CommandLogManager.warn("未找到应用: $name（可尝试用包名）")
            return false
        }
        // root：monkey 启动 launcher（比 am start -n 更稳，不需知道 Activity 全名）
        if (RootShellExecutor.checkRoot()) {
            val r = RootShellExecutor.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>/dev/null")
            if (r.ok) {
                CommandLogManager.ok("启动: $name → $pkg")
                return true
            }
        }
        // 非 root / root 失败兜底
        return try {
            val ctx = App.instance
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                CommandLogManager.ok("启动: $name → $pkg")
                true
            } else {
                CommandLogManager.warn("无启动入口: $name → $pkg")
                false
            }
        } catch (e: Throwable) {
            CommandLogManager.err("启动异常: $name | ${e.message}")
            false
        }
    }

    /** 刷新 label 缓存（卸装/安装后可调用）。 */
    fun refresh() {
        labelCache = null
    }

    private fun isInstalled(pkg: String): Boolean = try {
        App.instance.packageManager.getPackageInfo(pkg, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Throwable) {
        false
    }

    private fun ensureLabelCache(): Map<String, String> {
        labelCache?.let { return it }
        val map = HashMap<String, String>()
        try {
            val pm = App.instance.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val ris = pm.queryIntentActivities(intent, 0)
            for (ri in ris) {
                val pkg = ri.activityInfo.packageName
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Throwable) {
                    ""
                }
                if (label.isNotEmpty()) {
                    map[label] = pkg
                }
            }
        } catch (e: Throwable) {
            CommandLogManager.warn("构建应用列表失败: ${e.message}")
        }
        labelCache = map
        return map
    }
}
