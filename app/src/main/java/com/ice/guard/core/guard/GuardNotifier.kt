package com.ice.guard.core.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ice.guard.R
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.ui.guard.GuardCenterActivity

/**
 * 守护通知。
 *
 * 通知内容全部在本地生成（本应用没有 INTERNET 权限）。
 * 未授予通知权限时静默失败，不影响判定与处置链路本身。
 *
 * 通知上带一个「立即终止」动作——这是把"检测到"变成"已处理"最短的路径：
 * 用户点一下即可完成 force-stop + 冻结，无需先打开应用。
 */
object GuardNotifier {

    const val CHANNEL_ID = "ice_guard_alerts"
    private const val CHANNEL_NAME = "安全守护提醒"
    private const val NOTIFICATION_ID_BASE = 0xBEEF

    /** 展示一条守护通知 */
    fun show(context: Context, event: GuardEvent, canTerminate: Boolean) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)

            val level = RiskLevel.fromScore(event.score)
            val body = buildString {
                append("风险评分 ${event.score}（${level.label}）\n")
                append("触发通道：${event.channel.label}\n")
                append(event.detail.take(180))
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(titleOf(event))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openCenter(context))

            // 未真正处置、且具备终止能力时，给出最短路径的一键处置
            if (!event.verdict.acted && canTerminate) {
                builder.addAction(
                    R.drawable.ic_launcher_foreground,
                    "立即终止",
                    GuardActionReceiver.terminateIntent(context, event.packageName)
                )
            }

            nm.notify(
                NOTIFICATION_ID_BASE + (event.packageName.hashCode() and 0xFFFF),
                builder.build()
            )
        }
    }

    /** 构造状态栏常驻通知（守护服务 startForeground 时使用） */
    fun buildOngoing(context: Context, summary: String): android.app.Notification {
        ensureChannelFor(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ice 防护正在守护本机")
            .setContentText(summary)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openCenter(context))
            .build()
    }

    /** 更新常驻通知文案 */
    fun showOngoing(context: Context, summary: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
            nm.notify(ONGOING_ID, buildOngoing(context, summary))
        }
    }

    private fun ensureChannelFor(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
        }
    }

    fun cancelOngoing(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ONGOING_ID)
        }
    }

    private fun titleOf(event: GuardEvent): String = when (event.verdict) {
        GuardVerdict.FROZEN -> "已终止并冻结：${event.appLabel}"
        GuardVerdict.TERMINATED -> "已终止进程：${event.appLabel}"
        GuardVerdict.FAILED -> "处置未成功：${event.appLabel}"
        GuardVerdict.ALERTED -> "发现高风险应用：${event.appLabel}"
        GuardVerdict.OBSERVED -> "已记录：${event.appLabel}"
    }

    private fun openCenter(context: Context): PendingIntent {
        val intent = Intent(context, GuardCenterActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "检测到高风险应用，或已对其执行终止时提醒"
            }
        )
    }

    /** 常驻通知 ID，供守护服务 startForeground 复用 */
    const val ONGOING_ID = 0xBEE0
}