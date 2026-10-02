package com.ice.guard.core.guard

import android.content.Context
import com.ice.guard.core.privilege.ShizukuBridge
import com.ice.guard.di.AppContainer

/**
 * 全量巡检：开机自检与周期巡检共用的同一段逻辑。
 *
 * 做两件事：
 *  1. **全盘复检**——枚举已安装应用，对达到阈值的逐个走 [AutoResponder] 管线；
 *  2. **状态漂移检测**——检查此前被本应用「终止并冻结」的应用是否又回到了可运行状态。
 *
 * 第 2 点是巡检真正的价值所在：单次处置不是终点。
 * 被冻结的应用可能被用户手动启用、被其他应用拉活、或在系统更新后恢复，
 * 而这种"恢复"往往没有任何提示——只有复查才能发现。
 *
 * ## 边界
 * 巡检只能看到**状态**（停用与否、权限评分），看不到应用期间做过什么。
 */
object GuardSweep {

    data class Summary(
        val scanned: Int,
        val risky: Int,
        val acted: Int,
        val drifted: Int
    )

    /**
     * 执行一次全量巡检。**阻塞调用**，必须在后台线程执行。
     */
    fun run(context: Context, channel: GuardChannel): Summary {
        val policy = AppContainer.guardConfig.load()

        val apps = runCatching { AppContainer.permissionAudit.auditAll(includeSystem = false) }
            .getOrDefault(emptyList())
        val risky = apps.filter { it.score >= policy.threshold }

        var acted = 0
        risky.forEach { app ->
            val event = runCatching {
                AutoResponder.handle(context, app.packageName, channel)
            }.getOrNull()
            if (event?.verdict?.acted == true) acted++
        }

        val drifted = detectDrift(context, channel)

        return Summary(
            scanned = apps.size,
            risky = risky.size,
            acted = acted,
            drifted = drifted
        )
    }

    /**
     * 状态漂移检测：曾经被冻结、现在又能运行的应用。
     *
     * 查询失败时**不报漂移**——宁可漏报也不误报，否则用户会收到一条
     * "某应用复活了"的假警报，而实际上只是 Shizuku 没连上。
     */
    private fun detectDrift(context: Context, channel: GuardChannel): Int {
        if (!ShizukuBridge.isReady(context)) return 0

        val frozenBefore = AppContainer.interceptLog.actions()
            .filter { it.verdict == GuardVerdict.FROZEN }
            .distinctBy { it.packageName }
        if (frozenBefore.isEmpty()) return 0

        // null 表示查询失败，此时不做任何结论
        val stillDisabled = runCatching { AppContainer.disposal.disabledPackagesOrNull() }
            .getOrNull() ?: return 0
        if (stillDisabled.isEmpty()) {
            // 一个停用的都没有，却曾经成功冻结过——只可能是被启用了；
            // 但更可能是命令在个别 ROM 上返回了空结果，故仅在能列出内容时才判定。
            return 0
        }

        var count = 0
        frozenBefore.filter { it.packageName !in stillDisabled }.forEach { old ->
            val event = GuardEvent(
                timestamp = System.currentTimeMillis(),
                packageName = old.packageName,
                appLabel = old.appLabel,
                score = old.score,
                channel = channel,
                verdict = GuardVerdict.ALERTED,
                action = "状态漂移",
                detail = "此应用曾被终止并冻结，现在重新处于可运行状态" +
                    "（可能被手动启用，或被其他应用拉活）。建议重新执行处置。"
            )
            AppContainer.interceptLog.append(event)
            GuardNotifier.show(context, event, canTerminate = true)
            count++
        }
        return count
    }
}