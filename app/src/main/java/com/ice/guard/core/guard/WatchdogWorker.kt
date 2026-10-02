package com.ice.guard.core.guard

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 周期巡检（WorkManager）。
 *
 * WorkManager 的周期任务最小间隔为 **15 分钟**，这是系统的硬约束——
 * 因此这条通道定位是"兜底"，不是"实时"。真正的秒级响应由事件通道负责。
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { GuardSweep.run(applicationContext, GuardChannel.PERIODIC) }
        // 无论结果如何都返回 success：单次巡检失败没有重试价值，
        // 下一个周期自然会再跑一次。
        Result.success()
    }
}

/** 周期巡检的排程与取消。跟随「守护模式」开关启停。 */
object GuardScheduler {

    private const val WORK_NAME = "ice_guard_periodic_sweep"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            // 首次延迟 1 分钟，避免刚开机就与应用初始化抢资源
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP：已有排程时不打断，避免每次开应用都重置计时
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    /** 按当前策略同步排程：守护模式开启则排程，否则取消 */
    fun sync(context: Context) {
        if (com.ice.guard.di.AppContainer.guardConfig.load().guardMode) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    private const val INTERVAL_MINUTES = 15L
}