package com.ice.guard.core.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 新装应用广播接收器。
 *
 * 只处理「安装完成」——`PACKAGE_ADDED` 的 `EXTRA_REPLACING` 为 true 时表示
 * 应用被更新而非新装，此时跳过，避免用户每次升级都收到提醒。
 *
 * 重活（解析权限、算分）放在 goAsync 的背景线程里做，不阻塞主线程。
 */
class InstallMonitorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val result = InstallMonitor.audit(appContext, pkg)
                if (InstallMonitor.shouldAlert(result) && result != null) {
                    InstallMonitor.notifyIfNeeded(appContext, result)
                }
            } catch (t: Throwable) {
                // 广播链路不抛出：任何异常都只记录，不影响系统
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}
