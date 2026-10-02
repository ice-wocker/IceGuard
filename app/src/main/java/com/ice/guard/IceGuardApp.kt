package com.ice.guard

import android.app.Application
import com.ice.guard.core.privilege.ShizukuBridge
import com.ice.guard.di.AppContainer

/**
 * 应用入口。仅做依赖容器初始化，不做任何后台常驻行为。
 */
class IceGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        // 注册 Shizuku 的授权/服务监听；用户未安装 Shizuku 时这些调用是无副作用的空操作。
        ShizukuBridge.register()
    }
}
