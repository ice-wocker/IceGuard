package com.ice.guard.core.scanner

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel

/**
 * 模块 4 · 组件级清单分析。
 *
 * 权限之外，另一个高价值判定维度是**组件暴露面**：
 *  - 一个 `exported=true` 且无 `android:permission` 保护的组件，意味着**任意应用**都能唤起它；
 *  - 组件里声明的「绑定权限」（`android:permission` 为 BIND_* 系列）则等于对外宣告
 *    「本应用提供无障碍 / 通知读取 / 设备管理 / VPN 这类高敏感服务」。
 *
 * 后者往往比权限声明更有信息量：一个应用**声明**了无障碍权限不算罕见，
 * 但它**真的实现了一个无障碍服务**，就需要用户格外注意。
 *
 * ## 能力边界
 * 本分析只看**清单里的声明**，不判断组件实现是否存在真实漏洞，
 * 也不做动态调用验证。它回答的是"攻击面有多大"，不是"是否已被利用"。
 */
object ComponentAnalyzer {

    /** 统一后的组件描述。来源可以是已安装应用（PackageManager）或 APK（AXML）。 */
    data class ExportedComponent(
        val type: AxmlParser.ComponentType,
        val name: String,
        val exported: Boolean,
        val permission: String?,
        val actions: List<String> = emptyList()
    ) {
        /** 对外可见且没有任何权限门槛 */
        val unprotected: Boolean get() = exported && permission.isNullOrBlank()

        /**
         * 启动器入口。桌面图标对应的 Activity 天然是导出的，属于正常情况，
         * 不纳入「对外暴露」的统计——否则每个应用都会被误报一次。
         */
        val isLauncher: Boolean
            get() = type == AxmlParser.ComponentType.ACTIVITY &&
                actions.any { it == "android.intent.action.MAIN" }
    }

    /**
     * 高敏感「绑定权限」→ 说明与权重。
     * 应用只要在组件上声明了这些权限，就等于对外提供对应能力。
     */
    private val BINDING_SERVICES: Map<String, Pair<String, Int>> = mapOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to Pair("无障碍服务（可读屏、可模拟操作）", 70),
        "android.permission.BIND_VPN_SERVICE" to Pair("VPN 服务（可接管设备全部流量）", 70),
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to Pair("通知读取服务（可读取所有应用的通知内容）", 65),
        "android.permission.BIND_INPUT_METHOD" to Pair("输入法服务（可记录全部输入）", 65),
        "android.permission.BIND_DEVICE_ADMIN" to Pair("设备管理器（防卸载 / 可锁屏清数据）", 60),
        "android.permission.BIND_SCREENING_SERVICE" to Pair("通话筛查服务（可读取来电信息）", 45),
        "android.permission.BIND_CALL_REDIRECTION_SERVICE" to Pair("通话重定向服务", 50),
        "android.permission.BIND_TELECOM_CONNECTION_SERVICE" to Pair("通话连接服务", 50)
    )

    /** 从已安装应用的 PackageManager 数据提取组件。exported 由系统解析，含默认值。 */
    fun fromPackageInfo(info: PackageInfo): List<ExportedComponent> {
        val out = mutableListOf<ExportedComponent>()

        @Suppress("DEPRECATION")
        info.activities?.forEach { a ->
            out += ExportedComponent(
                type = AxmlParser.ComponentType.ACTIVITY,
                name = a.name.orEmpty(),
                exported = a.exported,
                permission = a.permission
            )
        }
        info.services?.forEach { s ->
            out += ExportedComponent(
                type = AxmlParser.ComponentType.SERVICE,
                name = s.name.orEmpty(),
                exported = s.exported,
                permission = s.permission
            )
        }
        @Suppress("DEPRECATION")
        info.receivers?.forEach { r: ActivityInfo ->
            out += ExportedComponent(
                type = AxmlParser.ComponentType.RECEIVER,
                name = r.name.orEmpty(),
                exported = r.exported,
                permission = r.permission
            )
        }
        info.providers?.forEach { p ->
            // ProviderInfo 没有单一的 permission 字段：读、写权限是分开声明的。
            // 只有读写都被保护时才算有门槛——否则读取仍可能对外敞开。
            val guarded = !p.readPermission.isNullOrBlank() && !p.writePermission.isNullOrBlank()
            out += ExportedComponent(
                type = AxmlParser.ComponentType.PROVIDER,
                name = p.name.orEmpty(),
                exported = p.exported,
                permission = if (guarded) p.readPermission else null
            )
        }
        return out
    }

    /** 从自研 AXML 解析结果提取组件（用于 APK 静态扫描）。 */
    fun fromManifest(manifest: AxmlParser.ManifestInfo): List<ExportedComponent> =
        manifest.components.map { c ->
            ExportedComponent(
                type = c.type,
                name = c.name,
                exported = c.exported,
                permission = c.permission,
                actions = c.actions
            )
        }

    /** 需要向 PackageManager 申请的 flag，供调用方构造 getPackageInfo 用 */
    const val PACKAGE_FLAGS: Int = PackageManager.GET_ACTIVITIES or
        PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or
        PackageManager.GET_PROVIDERS

    /**
     * 生成发现项。
     *
     * 精度优先：启动器入口与有权限门槛的组件都不计入「对外暴露」，
     * 只保留真正能被任意应用触达的部分，避免用噪音淹没用户。
     *
     * @param owner            归属对象（应用名或 APK 文件名），用于文案
     * @param launcherActivity 启动器 Activity 的完整类名（已安装应用可由
     *                         PackageManager 查到；AXML 来源则靠 isLauncher 判断）
     */
    fun findings(
        components: List<ExportedComponent>,
        owner: String,
        launcherActivity: String? = null
    ): List<Finding> {
        val out = mutableListOf<Finding>()

        val relevant = components.filterNot {
            it.isLauncher || (launcherActivity != null && it.name == launcherActivity)
        }

        // —— 1) 高敏感绑定服务：应用"真的提供了"这类能力 ——
        relevant
            .mapNotNull { c -> c.permission?.let { BINDING_SERVICES[it]?.let { m -> c to m } } }
            .distinctBy { it.second.first }
            .forEach { (component, meta) ->
                val (desc, weight) = meta
                out += Finding(
                    id = "component.binding.${component.permission}",
                    title = "内置$desc",
                    detail = "$owner 在清单中声明了组件「${component.name}」，" +
                        "对应权限 ${component.permission}，即该应用自带$desc 能力。" +
                        "请确认这是你主动安装并信任的应用。",
                    level = RiskLevel.fromScore(weight),
                    weight = weight,
                    category = Finding.Category.COMPONENT
                )
            }

        // —— 2) 对外暴露的组件（无权限门槛）——
        val exposed = relevant.filter { it.unprotected }

        exposed.count { it.type == AxmlParser.ComponentType.PROVIDER }.takeIf { it > 0 }?.let { n ->
            out += Finding(
                id = "component.exported.provider",
                title = "有 $n 个对外暴露的数据提供者",
                detail = "$owner 声明了 $n 个 exported=true 且无 android:permission 保护的 " +
                    "ContentProvider，其他应用可能直接访问其中的数据。" +
                    "组件：${preview(exposed, AxmlParser.ComponentType.PROVIDER)}",
                level = RiskLevel.HIGH,
                weight = 55,
                category = Finding.Category.COMPONENT
            )
        }

        exposed.count { it.type == AxmlParser.ComponentType.SERVICE }.takeIf { it > 0 }?.let { n ->
            out += Finding(
                id = "component.exported.service",
                title = "有 $n 个对外暴露的服务",
                detail = "$owner 声明了 $n 个可被任意应用启动的 Service（exported=true 且无权限保护）。" +
                    "组件：${preview(exposed, AxmlParser.ComponentType.SERVICE)}",
                level = RiskLevel.MEDIUM,
                weight = 30,
                category = Finding.Category.COMPONENT
            )
        }

        exposed.count { it.type == AxmlParser.ComponentType.RECEIVER }.takeIf { it > 0 }?.let { n ->
            out += Finding(
                id = "component.exported.receiver",
                title = "有 $n 个对外暴露的广播接收器",
                detail = "$owner 声明了 $n 个可被任意应用触发（sendBroadcast）的 Receiver" +
                    "（exported=true 且无权限保护）。组件：${preview(exposed, AxmlParser.ComponentType.RECEIVER)}",
                level = RiskLevel.MEDIUM,
                weight = 25,
                category = Finding.Category.COMPONENT
            )
        }

        exposed.count { it.type == AxmlParser.ComponentType.ACTIVITY }.takeIf { it > 0 }?.let { n ->
            out += Finding(
                id = "component.exported.activity",
                title = "有 $n 个对外暴露的界面",
                detail = "$owner 声明了 $n 个可被其他应用直接拉起的 Activity" +
                    "（exported=true 且无权限保护），常被用于互拉唤醒或伪造界面。",
                level = RiskLevel.LOW,
                weight = 25,
                category = Finding.Category.COMPONENT
            )
        }

        return out.sortedByDescending { it.weight }
    }

    private fun preview(list: List<ExportedComponent>, type: AxmlParser.ComponentType): String =
        list.filter { it.type == type }
            .take(3)
            .joinToString("、") { it.name.substringAfterLast('.') }
            .ifBlank { "—" }

    /** 调试版本标记：可被调试注入，属于分发场景下的异常特征。 */
    fun isDebuggable(flags: Int): Boolean = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 测试专用包标记 */
    fun isTestOnly(flags: Int): Boolean = (flags and ApplicationInfo.FLAG_TEST_ONLY) != 0
}