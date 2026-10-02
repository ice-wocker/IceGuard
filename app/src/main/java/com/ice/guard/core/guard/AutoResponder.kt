package com.ice.guard.core.guard

import android.content.Context
import com.ice.guard.core.privilege.ShizukuBridge
import com.ice.guard.core.scanner.PermissionAuditEngine
import com.ice.guard.di.AppContainer
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动响应管线：**四类检测通道的唯一汇合点**。
 *
 * 无论事件来自安装广播、开机自检、前台唤醒还是周期巡检，都走这一条路径：
 *
 * ```
 * 取分（带缓存）→ 读策略 → 决策 → （可选）执行终止 → 落日志 → 发通知
 * ```
 *
 * ## 决策优先级
 * 1. 评分未达阈值 → 忽略（**并且不写日志**，避免把日志变成流水账）；
 * 2. 达到阈值但未开启自动处置 / 未授权 Shizuku → 只告警；
 * 3. 达到阈值且允许自动处置 → `am force-stop`，按策略追加 `pm disable-user`。
 *
 * ## 关于「1 秒终止」
 * 这里的"快"来自**事件驱动**：事件一到就走完上面这条链路，终止本身由 Shizuku
 * 以 shell 权限执行，亚秒级生效。本应用看不到其他应用的行为，
 * 所以不存在"监控到发作再终止"——这一点在文档与界面上都如实标注。
 *
 * ## 性能
 * 前台通道每次窗口切换都会调用本管线，因此体检结果带 10 分钟缓存，
 * 避免高频切换应用时反复走 PackageManager 查询。
 */
object AutoResponder {

    /** 同一应用的重复处置间隔，避免前台通道把日志刷爆 */
    private const val THROTTLE_MS = 30_000L

    /** 体检结果缓存有效期。窗口切换很频繁，没有缓存会拖慢整机响应。 */
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val CACHE_MAX = 300

    private val lastHandled = ConcurrentHashMap<String, Long>()
    private val auditCache = ConcurrentHashMap<String, CachedAudit>()

    private class CachedAudit(
        val result: PermissionAuditEngine.AppAuditResult,
        val at: Long
    )

    /** 决策结果。抽成纯枚举是为了让判定逻辑可被单元测试覆盖。 */
    enum class Decision {
        /** 未达阈值，什么都不做 */
        IGNORE,

        /** 只发告警，不动应用 */
        ALERT_ONLY,

        /** 终止进程 */
        TERMINATE_ONLY,

        /** 终止进程并冻结 */
        TERMINATE_AND_FREEZE
    }

    /**
     * 纯决策函数：给定评分、策略与 Shizuku 可用性，返回该做什么。
     *
     * @param forced 用户主动触发（通知按钮 / 守护中心）时为 true，
     *               此时不受阈值与自动处置开关限制——因为是用户自己点的。
     */
    fun decide(
        score: Int,
        policy: GuardPolicy,
        shizukuReady: Boolean,
        forced: Boolean = false
    ): Decision = when {
        forced && !shizukuReady -> Decision.ALERT_ONLY
        forced -> if (policy.freeze) Decision.TERMINATE_AND_FREEZE else Decision.TERMINATE_ONLY
        !policy.shouldRespond(score) -> Decision.IGNORE
        policy.autoRespond && shizukuReady && (policy.terminate || policy.freeze) ->
            if (policy.freeze) Decision.TERMINATE_AND_FREEZE else Decision.TERMINATE_ONLY
        else -> Decision.ALERT_ONLY
    }

    /**
     * 处理一次检测事件。**阻塞调用**，必须在后台线程执行。
     *
     * @return 落库的记录；未达阈值或已被节流时返回 null
     */
    @Synchronized
    fun handle(context: Context, packageName: String, channel: GuardChannel): GuardEvent? {
        if (packageName.isBlank()) return null
        if (packageName == context.packageName) return null

        val policy = AppContainer.guardConfig.load()
        val app = audit(context, packageName) ?: return null

        // 绝大多数应用在这里就出局，不会产生任何日志与通知
        if (!policy.shouldRespond(app.score)) return null
        if (throttled(packageName)) return null

        return respond(context, app, policy, channel, forced = false)
    }

    /**
     * 用户主动处置（通知上的「立即终止」、守护中心里的按钮）。
     * 不受自动处置开关限制，但仍需要 Shizuku 授权才能真正生效。
     */
    @Synchronized
    fun manualRespond(context: Context, packageName: String): GuardEvent? {
        if (packageName.isBlank()) return null
        // 手动处置要反映当前状态，跳过缓存
        invalidate(packageName)
        val app = audit(context, packageName) ?: return null
        val policy = AppContainer.guardConfig.load()
        return respond(context, app, policy, GuardChannel.MANUAL, forced = true)
    }

    /** 应用被安装 / 更新后，其缓存分数不再可信 */
    fun invalidate(packageName: String) {
        auditCache.remove(packageName)
    }

    private fun respond(
        context: Context,
        app: PermissionAuditEngine.AppAuditResult,
        policy: GuardPolicy,
        channel: GuardChannel,
        forced: Boolean
    ): GuardEvent {
        val shizukuReady = ShizukuBridge.isReady(context)
        val decision = decide(app.score, policy, shizukuReady, forced)

        val event = when (decision) {
            Decision.IGNORE -> GuardEvent(
                timestamp = System.currentTimeMillis(),
                packageName = app.packageName,
                appLabel = app.appLabel,
                score = app.score,
                channel = channel,
                verdict = GuardVerdict.OBSERVED,
                action = "无动作",
                detail = "风险分 ${app.score}，未达到阈值 ${policy.threshold}"
            )

            Decision.ALERT_ONLY -> GuardEvent(
                timestamp = System.currentTimeMillis(),
                packageName = app.packageName,
                appLabel = app.appLabel,
                score = app.score,
                channel = channel,
                verdict = GuardVerdict.ALERTED,
                action = "仅告警",
                detail = alertReason(policy, shizukuReady, forced)
            )

            Decision.TERMINATE_ONLY, Decision.TERMINATE_AND_FREEZE -> {
                val freeze = decision == Decision.TERMINATE_AND_FREEZE
                val started = System.currentTimeMillis()
                val results = runCatching { AppContainer.disposal.terminate(app.packageName, freeze) }
                    .getOrDefault(emptyList())
                val elapsed = System.currentTimeMillis() - started

                val stopped = results.firstOrNull()?.ok == true
                val freezeAttempted = freeze && results.size > 1
                val frozen = freezeAttempted && results[1].ok

                GuardEvent(
                    timestamp = started,
                    packageName = app.packageName,
                    appLabel = app.appLabel,
                    score = app.score,
                    channel = channel,
                    verdict = when {
                        !stopped -> GuardVerdict.FAILED
                        frozen -> GuardVerdict.FROZEN
                        else -> GuardVerdict.TERMINATED
                    },
                    action = buildString {
                        append("am force-stop")
                        if (freezeAttempted) append(" + pm disable-user")
                    },
                    detail = if (results.isEmpty()) {
                        "未能执行命令（Shizuku 未就绪）"
                    } else {
                        "耗时 ${elapsed}ms；" + results.joinToString("；") { it.summary() }
                    },
                    viaShizuku = results.firstOrNull()?.viaShizuku == true
                )
            }
        }

        // 只记录"需要用户知道"的事件，让日志保持可读
        if (event.verdict != GuardVerdict.OBSERVED) {
            AppContainer.interceptLog.append(event)
            markHandled(app.packageName)
            GuardNotifier.show(context, event, canTerminate = shizukuReady)
        }
        return event
    }

    private fun alertReason(policy: GuardPolicy, shizukuReady: Boolean, forced: Boolean): String = when {
        forced && !shizukuReady -> "尚无法终止：请先在「权限与处置」页完成 Shizuku 授权"
        forced -> "风险分达到关注区间"
        !policy.autoRespond -> "风险分达到阈值 ${policy.threshold}，自动处置未开启（默认关闭），仅告警"
        !shizukuReady -> "风险分达到阈值 ${policy.threshold}，但未获得 Shizuku 授权，无法执行终止"
        else -> "风险分达到阈值 ${policy.threshold}"
    }

    /**
     * 带缓存的体检。前台通道每秒都可能调用，没有缓存会拖慢整机。
     * 仅在缓存缺失或过期时才真正查询 PackageManager。
     */
    private fun audit(context: Context, packageName: String): PermissionAuditEngine.AppAuditResult? {
        val now = System.currentTimeMillis()
        auditCache[packageName]?.let { cached ->
            if (now - cached.at < CACHE_TTL_MS) return cached.result
        }

        val result = runCatching { AppContainer.permissionAudit.auditOne(packageName) }
            .getOrNull() ?: return null

        if (auditCache.size >= CACHE_MAX) {
            auditCache.entries.removeAll { now - it.value.at > CACHE_TTL_MS }
        }
        auditCache[packageName] = CachedAudit(result, now)
        return result
    }

    private fun throttled(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastHandled[packageName] ?: return false
        return now - last < THROTTLE_MS
    }

    private fun markHandled(packageName: String) {
        val now = System.currentTimeMillis()
        // 顺手清理过期条目，避免长期运行后 map 无限增长
        lastHandled.entries.removeAll { now - it.value > THROTTLE_MS * 4 }
        lastHandled[packageName] = now
    }
}