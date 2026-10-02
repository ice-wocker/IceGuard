package com.ice.guard.core.guard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知动作接收器：承载通知上「立即终止」按钮的点击。
 *
 * 只接受显式 Intent（`setPackage` 限制在自身包内），不接受外部应用伪造的广播——
 * 这个接收器一旦被外部触发，就等于让别的应用借本应用之手停用任意应用。
 */
class GuardActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TERMINATE) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() } ?: return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                AutoResponder.manualRespond(appContext, pkg)
            } catch (t: Throwable) {
                // 接收器链路绝不抛出
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    companion object {
        const val ACTION_TERMINATE = "com.ice.guard.action.TERMINATE"
        const val EXTRA_PACKAGE = "extra_package"

        /** 构造「立即终止」按钮的 PendingIntent。requestCode 按包名区分，避免互相覆盖。 */
        fun terminateIntent(context: Context, packageName: String): PendingIntent {
            val intent = Intent(context, GuardActionReceiver::class.java).apply {
                action = ACTION_TERMINATE
                // 限定接收者：其他应用无法伪造本广播
                setPackage(context.packageName)
                putExtra(EXTRA_PACKAGE, packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}