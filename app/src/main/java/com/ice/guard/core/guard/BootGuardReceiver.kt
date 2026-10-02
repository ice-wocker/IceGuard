package com.ice.guard.core.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自检 —— 守护的第二条通道。
 *
 * 开机后做三件事：
 *  1. 重新排程周期巡检（部分 ROM 会在关机时清掉 WorkManager 的排程）；
 *  2. 按策略恢复守护服务的启停状态；
 *  3. 跑一次全量巡检 [GuardSweep]，检查评分变化与"冻结后又被启用"的状态漂移。
 *
 * 为什么开机这一趟特别重要：设备重启后，此前的处置结果可能已经失效
 * （应用被重新启用、系统更新还原了权限），而用户不会收到任何提示。
 */
class BootGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                GuardScheduler.sync(appContext)
                GuardService.sync(appContext)
                GuardSweep.run(appContext, GuardChannel.BOOT)
            } catch (t: Throwable) {
                // 广播链路绝不抛出
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }
}