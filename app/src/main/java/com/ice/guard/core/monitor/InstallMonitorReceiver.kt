package com.ice.guard.core.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ice.guard.core.guard.AutoResponder
import com.ice.guard.core.guard.GuardChannel
import com.ice.guard.di.AppContainer

/**
 * 安装 / 更新事件接收器 —— 守护的第一条通道。
 *
 * - `PACKAGE_ADDED`（非替换）→ [GuardChannel.INSTALL]
 * - `PACKAGE_ADDED` 且 `EXTRA_REPLACING=true`、或 `PACKAGE_REPLACED` → [GuardChannel.UPDATE]
 *
 * 重活（解析权限、算分、可能的终止命令）放在 `goAsync` 的背景线程里做，不阻塞主线程；
 * 广播链路**绝不抛出**，任何异常只记录，不影响系统。
 */
class InstallMonitorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val channel = channelOf(intent) ?: return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val policy = AppContainer.guardConfig.load()
                // 快速短路：绝大多数应用无风险，不必进完整管线
                val pre = InstallMonitor.audit(appContext, pkg)
                if (InstallMonitor.shouldAlert(pre, policy.threshold)) {
                    AutoResponder.handle(appContext, pkg, channel)
                }
            } catch (t: Throwable) {
                // 广播链路不抛出：任何异常都只记录，不影响系统
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    private fun channelOf(intent: Intent): GuardChannel? = when (intent.action) {
        Intent.ACTION_PACKAGE_ADDED ->
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                GuardChannel.UPDATE
            } else {
                GuardChannel.INSTALL
            }

        Intent.ACTION_PACKAGE_REPLACED -> GuardChannel.UPDATE
        else -> null
    }
}