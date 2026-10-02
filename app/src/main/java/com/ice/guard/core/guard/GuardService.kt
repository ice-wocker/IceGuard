package com.ice.guard.core.guard

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ice.guard.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 守护服务：承载「持续观测」的常驻前台服务。
 *
 * ## 它负责什么
 * - 挂一个**常驻通知**，让"正在守护"这件事在状态栏可见、可随时关闭
 *   （而不是一个偷偷在后台跑的服务——本项目不做那种事）；
 * - 承载 [ForegroundAppWatcher] 的秒级前台唤醒轮询（需用户另行授予
 *   「使用情况访问」权限，未授权时该轮询自动跳过）。
 *
 * ## 它不负责什么
 * - 不做自动处置决策——那是 [AutoResponder] 的职责，无论服务是否在跑都一样；
 * - 不做任何网络行为（本应用没有 INTERNET 权限）。
 *
 * 一个诚实的副作用说明：前台服务会占用一条常驻通知，这是 Android 的硬性要求，
 * 无法省略。用户可在守护中心一键关闭。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var watcher: ForegroundAppWatcher

    override fun onCreate() {
        super.onCreate()
        watcher = ForegroundAppWatcher(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val policy = AppContainer.guardConfig.load()

        // 前台服务必须在 5 秒内 startForeground，否则会被系统判定为 ANR
        startForegroundCompat(summaryOf(policy))

        if (policy.watchForeground && ForegroundAppWatcher.hasPermission(this)) {
            watcher.start(scope)
        } else {
            watcher.stop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher.stop()
        scope.cancel()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun summaryOf(policy: GuardPolicy): String = buildString {
        append("自动处置：")
        append(if (policy.autoRespond) "已开启" else "关闭")
        append(" · 告警阈值：")
        append(policy.threshold)
        if (policy.watchForeground && !ForegroundAppWatcher.hasPermission(this@GuardService)) {
            append(" · 前台监听待授权")
        }
    }

    private fun startForegroundCompat(summary: String) {
        val notification = GuardNotifier.buildOngoing(this, summary)
        // Android 14 起必须声明前台服务类型；specialUse 用于"本地安全守护"这类无法归类的用途
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, GuardNotifier.ONGOING_ID, notification, type)
        }
    }

    companion object {
        const val ACTION_START = "com.ice.guard.action.GUARD_START"
        const val ACTION_STOP = "com.ice.guard.action.GUARD_STOP"

        /** 服务是否在运行。仅用于界面展示，不做控制流判断。 */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GuardService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
            GuardNotifier.cancelOngoing(context)
        }

        /**
         * 按当前策略同步服务的启停状态。
         * 用户每次在守护中心改开关，或开机自检时，都调用它一次。
         */
        fun sync(context: Context) {
            if (AppContainer.guardConfig.load().guardMode) start(context) else stop(context)
        }
    }
}