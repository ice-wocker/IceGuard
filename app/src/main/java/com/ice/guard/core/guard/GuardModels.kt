package com.ice.guard.core.guard

import org.json.JSONObject

/**
 * 守护域模型：策略、事件通道、处置结论、拦截日志条目。
 *
 * ## 关于「1 秒终止」的诚实说明
 * 本应用**无法**观测其他应用的行为，因此不存在"监控到病毒发作再终止"这种能力——
 * Android 的沙箱隔离决定了第三方应用看不到别的进程的内存与行为。
 *
 * 能做到的是**事件驱动的秒级响应**：在四类可观测事件（安装 / 开机 / 前台唤醒 / 周期巡检）
 * 发生时立即判定并处置。其中「终止」这一步是真实生效的：
 * 经 Shizuku 授权后 `am force-stop` 会在亚秒级真正杀掉目标进程，
 * 并可进一步 `pm disable-user` 冻结，使其无法自动重启。
 *
 * 判定依据仍是权限组合的启发式评分，**不是**"确认它是病毒"。
 */

/** 触发判定的通道。记录通道是为了让用户能核验"这次处置是被什么触发的"。 */
enum class GuardChannel(val label: String) {
    INSTALL("安装事件"),
    UPDATE("更新事件"),
    BOOT("开机自检"),
    FOREGROUND("前台唤醒"),
    ACCESSIBILITY("前台事件（无障碍）"),
    PERIODIC("周期巡检"),
    MANUAL("手动处置")
}

/** 一次判定的最终结论。 */
enum class GuardVerdict(val label: String, val acted: Boolean) {
    /** 低于阈值，仅记录 */
    OBSERVED("已观察", false),

    /** 达到阈值但未开启自动处置，已发告警 */
    ALERTED("已告警", false),

    /** 已 force-stop 终止进程 */
    TERMINATED("已终止进程", true),

    /** 已终止并冻结（force-stop + disable-user），无法自动重启 */
    FROZEN("已终止并冻结", true),

    /** 判定达到阈值，但处置未成功 */
    FAILED("处置未成功", false)
}

/**
 * 一条守护记录。
 * 这是「本地拦截日志」的最小单元，用于在守护中心页如实展示每一次判定与处置。
 */
data class GuardEvent(
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val score: Int,
    val channel: GuardChannel,
    val verdict: GuardVerdict,
    /** 实际执行的动作（未处置时为判定依据摘要） */
    val action: String,
    /** 命令返回或原因说明，失败时必须能看出为什么失败 */
    val detail: String,
    /** 是否经 Shizuku（shell 权限）执行 */
    val viaShizuku: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("score", score)
        put("channel", channel.name)
        put("verdict", verdict.name)
        put("action", action)
        put("detail", detail)
        put("viaShizuku", viaShizuku)
    }

    companion object {
        fun fromJson(o: JSONObject?): GuardEvent? {
            if (o == null) return null
            return try {
                GuardEvent(
                    timestamp = o.optLong("timestamp"),
                    packageName = o.optString("packageName"),
                    appLabel = o.optString("appLabel"),
                    score = o.optInt("score"),
                    channel = runCatching { GuardChannel.valueOf(o.optString("channel")) }
                        .getOrDefault(GuardChannel.MANUAL),
                    verdict = runCatching { GuardVerdict.valueOf(o.optString("verdict")) }
                        .getOrDefault(GuardVerdict.OBSERVED),
                    action = o.optString("action"),
                    detail = o.optString("detail"),
                    viaShizuku = o.optBoolean("viaShizuku")
                )
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/**
 * 守护策略。
 *
 * 默认值刻意保守：**自动处置默认关闭**。默认状态下本应用只做「检测 + 告警 + 一键处置」，
 * 不会在用户不知情时动任何应用。用户主动打开开关后，才会在评分达到阈值时自动终止。
 */
data class GuardPolicy(
    /** 自动处置总开关。默认关闭。 */
    val autoRespond: Boolean = false,
    /** 触发自动处置的分数阈值 */
    val threshold: Int = DEFAULT_THRESHOLD,
    /** 是否执行 `am force-stop` 终止进程 */
    val terminate: Boolean = true,
    /** 是否在终止后执行 `pm disable-user` 冻结（防止自动重启） */
    val freeze: Boolean = true,
    /** 是否启用前台唤醒监听（需「使用情况访问」特殊授权） */
    val watchForeground: Boolean = false,
    /** 是否启用无障碍通道（需在系统设置中手动开启；仅观察，不模拟操作） */
    val watchAccessibility: Boolean = false,

    /**
     * 守护模式总开关：常驻前台服务 + 周期巡检。
     *
     * 与 [autoRespond] 是两个独立的开关，刻意不合并：
     * 守护模式只决定"要不要持续盯着"，自动处置决定"盯到之后动不动手"。
     * 用户可以只开守护模式、不做任何自动处置（默认组合就是如此）。
     */
    val guardMode: Boolean = false
) {
    /** 达到阈值即需要关注 */
    fun shouldRespond(score: Int): Boolean = score >= threshold

    companion object {
        /** 默认阈值。低于该值会频繁打扰用户，故锁定在高级风险区间下界。 */
        const val DEFAULT_THRESHOLD = 65

        /** 阈值可选范围 */
        val THRESHOLD_RANGE = 40..95
    }
}