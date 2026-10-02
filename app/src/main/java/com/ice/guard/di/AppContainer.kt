package com.ice.guard.di

import android.content.Context
import com.ice.guard.core.scanner.ApkScanEngine
import com.ice.guard.core.scanner.EnvironmentCheckEngine
import com.ice.guard.core.privilege.DisposalEngine
import com.ice.guard.core.scanner.PermissionAuditEngine
import com.ice.guard.data.GuardConfigStore
import com.ice.guard.data.InterceptLogStore
import com.ice.guard.data.ScanHistoryStore

/**
 * 自研极简依赖容器（不引入 Hilt / Koin）。
 *
 * 设计取舍：单例通过 lazy 延迟创建，避免应用启动时一次性构造全部引擎。
 * 所有依赖均为无状态或仅持有 Context 的引擎对象，因此不需要复杂的生命周期管理。
 */
object AppContainer {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val permissionAudit: PermissionAuditEngine by lazy { PermissionAuditEngine(appContext) }

    val apkScan: ApkScanEngine by lazy { ApkScanEngine(appContext) }

    val environmentCheck: EnvironmentCheckEngine by lazy { EnvironmentCheckEngine(appContext) }

    val disposal: DisposalEngine by lazy { DisposalEngine(appContext) }

    val historyStore: ScanHistoryStore by lazy { ScanHistoryStore(appContext) }

    /** 守护策略（自动处置开关、阈值、各通道开关） */
    val guardConfig: GuardConfigStore by lazy { GuardConfigStore(appContext) }

    /** 本地拦截日志 */
    val interceptLog: InterceptLogStore by lazy { InterceptLogStore(appContext) }
}
