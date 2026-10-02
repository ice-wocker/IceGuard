package com.ice.guard.core.guard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台唤醒监听 —— 守护的第三条通道。
 *
 * 通过 `UsageStatsManager.queryEvents` 轮询"哪个应用刚被切到前台"，
 * 目标应用一被打开就进入判定管线。这是**不需要无障碍权限**就能拿到的
 * 最接近实时的观测手段。
 *
 * ## 代价与边界（如实声明）
 * - 需要用户在系统设置中授予「使用情况访问」**特殊权限**（不是普通运行时权限）；
 * - 轮询间隔默认 1 秒，是**秒级**而非严格实时，且会带来一定的电量开销，
 *   因此默认关闭，由用户在守护中心自行开启；
 * - 它只能回答"哪个应用在前台"，**回答不了"这个应用正在做什么"**。
 * - `queryEvents` 返回的是系统记录的历史事件，即使应用已被终止，
 *   本监听也不会知道"它刚才做了什么"。
 */
class ForegroundAppWatcher(private val context: Context) {

    private var job: Job? = null
    private var lastQueryTime: Long = 0L
    private var lastPackage: String? = null

    /** 开始轮询。重复调用会先停掉上一轮。 */
    fun start(scope: CoroutineScope, intervalMillis: Long = DEFAULT_INTERVAL_MS) {
        stop()
        lastQueryTime = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                runCatching { poll() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val events = runCatching { usm.queryEvents(lastQueryTime, now) }.getOrNull() ?: return
        lastQueryTime = now

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != RESUMED_EVENT_TYPE) continue

            val pkg = event.packageName ?: continue
            if (pkg == lastPackage) continue
            if (pkg == context.packageName) continue
            lastPackage = pkg

            // 走统一管线。AutoResponder 内部有 10 分钟体检缓存 + 30 秒处置节流，
            // 因此高频切换应用不会造成可感知的开销。
            AutoResponder.handle(context, pkg, GuardChannel.FOREGROUND)
        }
    }

    companion object {
        /** 轮询间隔。1 秒是"秒级响应"与耗电之间的折中。 */
        const val DEFAULT_INTERVAL_MS = 1_000L

        /** 前台事件类型：API 29 起为 ACTIVITY_RESUMED，之前为 MOVE_TO_FOREGROUND */
        private val RESUMED_EVENT_TYPE: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        /**
         * 是否已获得「使用情况访问」授权。
         *
         * 该权限属于 AppOps 特殊权限，默认值为 MODE_DEFAULT（即拒绝），
         * 只有显式授权后才会是 MODE_ALLOWED。
         */
        fun hasPermission(context: Context): Boolean = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}