package com.ice.guard.core.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ice.guard.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍通道 —— 守护的第四条通道。**只观察，绝不操作。**
 *
 * ## 为什么是"观察态"而不是"操控态"
 * 市面上不少安全类应用用无障碍权限**模拟点击**去做"一键清理"，
 * 那既无法验证，又滥用了这项高危权限。本服务刻意与之相反：
 *
 * - 配置里 `canRetrieveWindowContent="false"` —— **读不到任何屏幕内容**，
 *   只能拿到"哪个应用的窗口发生了变化"这一个字段；
 * - 不声明 `canPerformGestures`，代码中也**不存在** `dispatchGesture` /
 *   `performAction` 调用 —— 没有能力，也没有意图模拟用户操作；
 * - 拿到包名后立即交给 [AutoResponder]，处置动作一律通过 Shizuku 的 `pm` / `am`
 *   命令执行，而不是在界面上"点"。
 *
 * 换句话说：本服务做的事情，与「哪个应用刚被切到前台」这一条信息等价，
 * 只是比轮询 [ForegroundAppWatcher] 更快、更省电。
 *
 * ## 边界
 * 它仍然回答不了"这个应用正在做什么"——无障碍事件只描述窗口变化，
 * 不描述行为。开启它是为了**更早发现**，不是为了"监控行为"。
 */
class GuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 用户在系统设置里开启了服务，同步策略开关，
        // 避免出现"系统开着、策略关着"的认知不一致
        runCatching {
            AppContainer.guardConfig.update { it.copy(watchAccessibility = true) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 只取包名。配置中未开启 canRetrieveWindowContent，这里也读不到别的东西。
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg == lastPackage) return
        lastPackage = pkg

        // 策略未开启时不做任何事（用户可能只在系统里开着服务，但没启用本通道）
        if (!AppContainer.guardConfig.load().watchAccessibility) return

        scope.launch {
            runCatching {
                AutoResponder.handle(applicationContext, pkg, GuardChannel.ACCESSIBILITY)
            }
        }
    }

    override fun onInterrupt() {
        // 无需处理：本服务不执行任何操作，因此不存在"被中断的操作"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}