package com.ice.guard.data

import android.content.Context
import com.ice.guard.core.guard.GuardPolicy

/**
 * 守护策略存储（SharedPreferences，自研轻量实现）。
 *
 * 策略项少且都是标量，无需数据库；读取失败时一律回退到**保守默认值**
 * （自动处置关闭），避免因存储损坏而意外开启自动处置。
 */
class GuardConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("ice_guard_policy", Context.MODE_PRIVATE)

    fun load(): GuardPolicy {
        val threshold = prefs.getInt(KEY_THRESHOLD, GuardPolicy.DEFAULT_THRESHOLD)
            .coerceIn(GuardPolicy.THRESHOLD_RANGE)
        return GuardPolicy(
            autoRespond = prefs.getBoolean(KEY_AUTO_RESPOND, false),
            threshold = threshold,
            terminate = prefs.getBoolean(KEY_TERMINATE, true),
            freeze = prefs.getBoolean(KEY_FREEZE, true),
            watchForeground = prefs.getBoolean(KEY_WATCH_FOREGROUND, false),
            watchAccessibility = prefs.getBoolean(KEY_WATCH_ACCESSIBILITY, false),
            guardMode = prefs.getBoolean(KEY_GUARD_MODE, false)
        )
    }

    fun save(policy: GuardPolicy) {
        prefs.edit()
            .putBoolean(KEY_AUTO_RESPOND, policy.autoRespond)
            .putInt(KEY_THRESHOLD, policy.threshold.coerceIn(GuardPolicy.THRESHOLD_RANGE))
            .putBoolean(KEY_TERMINATE, policy.terminate)
            .putBoolean(KEY_FREEZE, policy.freeze)
            .putBoolean(KEY_WATCH_FOREGROUND, policy.watchForeground)
            .putBoolean(KEY_WATCH_ACCESSIBILITY, policy.watchAccessibility)
            .putBoolean(KEY_GUARD_MODE, policy.guardMode)
            .apply()
    }

    /** 读出 → 改字段 → 写回。避免调用方漏写未修改的项。 */
    fun update(transform: (GuardPolicy) -> GuardPolicy) {
        save(transform(load()))
    }

    companion object {
        private const val KEY_AUTO_RESPOND = "auto_respond"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_TERMINATE = "terminate"
        private const val KEY_FREEZE = "freeze"
        private const val KEY_WATCH_FOREGROUND = "watch_foreground"
        private const val KEY_WATCH_ACCESSIBILITY = "watch_accessibility"
        private const val KEY_GUARD_MODE = "guard_mode"
    }
}