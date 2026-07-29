package com.ai.agentcontroller

import android.content.Context
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * 无障碍 / 权限相关辅助检测。
 */
object AccessibilityServiceHelper {

    private val ctx: Context get() = App.instance

    /** 我们的无障碍服务是否已启用。 */
    fun isEnabled(): Boolean {
        val expected = ctx.packageName + "/" + AgentAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(ctx)

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** 跳转到无障碍设置页。 */
    fun openAccessibilitySettings() {
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** 跳转到悬浮窗权限页。 */
    fun openOverlaySettings() {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + ctx.packageName)
        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        ctx.startActivity(intent)
    }
}
