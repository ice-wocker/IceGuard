package com.ice.guard.core.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ice.guard.R
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.core.scanner.PermissionAuditEngine

/**
 * 新装应用自动体检 + 告警。
 *
 * ## 这是本应用能做到的「最接近拦截」的合法形态
 * 监听系统的 `ACTION_PACKAGE_ADDED` 广播——**不需要任何特殊权限**，
 * 新应用安装完成后立刻对它跑一遍权限组合评分；若达到告警阈值，
 * 发一条本地通知，用户点进去可在审计页做处置。
 *
 * ## 边界（如实声明）
 * - 广播是**安装完成后**才收到的，所以这是「装后告警」，不是「安装前拦截」。
 *   系统没有给第三方应用「阻止安装」的 API，Android 14+ 更是收紧了这个口子。
 * - 若用户已通过 Shizuku 授权，可在通知/审计页一键**停用或卸载**该应用，
 *   这是「事后处置」；配合本应用的启发式评分，就构成一条
 *   「自动检测 → 告警 → 用户确认 → 处置」的完整链路。
 */
object InstallMonitor {

    const val CHANNEL_ID = "ice_guard_install_monitor"
    private const val CHANNEL_NAME = "新装应用风险提醒"
    private const val NOTIFICATION_ID_BASE = 0x1CE

    /** 达到或超过该分数即发出告警通知 */
    const val ALERT_THRESHOLD = 65

    /**
     * 对刚安装的应用做一次体检，返回结果（供通知与日志使用）。
     * 任何异常都吞掉并返回 null——广播接收器里不能崩。
     */
    fun audit(context: Context, packageName: String): PermissionAuditEngine.AppAuditResult? =
        runCatching { PermissionAuditEngine(context).auditOne(packageName) }.getOrNull()

    /** 是否需要告警 */
    fun shouldAlert(result: PermissionAuditEngine.AppAuditResult?): Boolean =
        result != null && result.score >= ALERT_THRESHOLD

    /**
     * 发出告警通知。通知内容完全在本地生成，不联网。
     * 未授予通知权限时静默失败，不影响主流程。
     */
    fun notifyIfNeeded(context: Context, result: PermissionAuditEngine.AppAuditResult) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)

            val level = RiskLevel.fromScore(result.score)
            val body = buildString {
                append("风险评分 ${result.score}（${level.label}）\n")
                if (result.findings.isNotEmpty()) {
                    append(result.findings.take(2).joinToString("\n") { "· ${it.title}" })
                } else {
                    append("· 声明权限 ${result.declaredPermissions.size} 项")
                }
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("新装应用需关注：${result.appLabel}")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(NOTIFICATION_ID_BASE + (result.packageName.hashCode() and 0xFFFF), notification)
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "检测到新安装的应用存在高风险权限组合时提醒"
            }
        )
    }
}
