package com.ice.guard.core.monitor

import android.content.Context
import com.ice.guard.core.guard.GuardPolicy
import com.ice.guard.core.scanner.PermissionAuditEngine

/**
 * 安装 / 更新通道的入口判定。
 *
 * ## 这是本应用能做到的「最接近拦截」的合法形态
 * 监听系统的 `ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REPLACED` 广播——
 * **不需要任何特殊权限**，应用装完或更新完成后立刻跑一遍权限组合评分。
 *
 * ## 边界（如实声明）
 * - 广播是**安装完成后**才收到的，所以这是「装后告警 / 装后处置」，不是「安装前拦截」。
 *   系统没有给第三方应用「阻止安装」的 API。
 * - 更新同样会触发判定：一次更新把无害应用变成高危应用，是真实存在的攻击路径。
 *
 * 判定达到阈值后由 [com.ice.guard.core.guard.AutoResponder] 统一处置
 * （告警 或 终止 + 冻结），本对象只负责"取分"这一件事。
 */
object InstallMonitor {

    /**
     * 安装通道的默认告警阈值。
     * 与 [GuardPolicy.DEFAULT_THRESHOLD] 保持一致：低于 65 会频繁打扰用户，故锁定下界。
     */
    const val ALERT_THRESHOLD = GuardPolicy.DEFAULT_THRESHOLD

    /**
     * 对刚安装 / 刚更新的应用做一次体检。
     * 任何异常都吞掉并返回 null——广播接收器里不能崩。
     */
    fun audit(context: Context, packageName: String): PermissionAuditEngine.AppAuditResult? =
        runCatching { PermissionAuditEngine(context).auditOne(packageName) }.getOrNull()

    /**
     * 是否需要进入处置管线。
     *
     * 这是一个**快速短路**：与 `AutoResponder.decide` 使用同一个「评分 ≥ 阈值」谓词，
     * 目的是让绝大多数无风险的应用连完整管线都不用进，而不是另一套判定标准。
     */
    fun shouldAlert(
        result: PermissionAuditEngine.AppAuditResult?,
        threshold: Int = ALERT_THRESHOLD
    ): Boolean = result != null && result.score >= threshold
}