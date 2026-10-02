package com.ice.guard.core.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.PermissionRules
import com.ice.guard.core.rules.RiskLevel

/**
 * 模块 1 · 应用权限审计引擎。
 *
 * 能力边界（诚实声明）：
 *  - 本引擎读取的是「应用声明的权限」与「是否已授予」，这两项通过 PackageManager 可公开查询。
 *  - 本引擎不监控应用的运行时行为，也无法判断权限是否正在被滥用。
 *  - 风险分为启发式评估，用于提示用户关注，不等同于「该应用是恶意的」这一结论。
 */
class PermissionAuditEngine(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    private val signatures = SignatureInspector(context)

    /** 单个应用的审计结果 */
    data class AppAuditResult(
        val packageName: String,
        val appLabel: String,
        val isSystem: Boolean,
        val declaredPermissions: List<String>,
        val score: Int,
        val level: RiskLevel,
        val findings: List<Finding>,
        /** 清单中声明的组件（用于展示暴露面） */
        val components: List<ComponentAnalyzer.ExportedComponent> = emptyList(),
        /** 安装来源与签名信息；取不到时为 null */
        val source: SignatureInspector.InstallSource? = null
    )

    /**
     * 审计全部已安装应用。
     * @param includeSystem 是否包含系统应用（默认排除，避免噪音）
     */
    fun auditAll(includeSystem: Boolean = false): List<AppAuditResult> {
        val packages: List<PackageInfo> = try {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or ComponentAnalyzer.PACKAGE_FLAGS)
        } catch (t: Throwable) {
            emptyList()
        }

        return packages
            .asSequence()
            .filter { it.packageName != context.packageName }
            .mapNotNull { safeAudit(it) }
            .filter { includeSystem || !it.isSystem }
            .sortedByDescending { it.score }
            .toList()
    }

    /** 审计单个包名 */
    fun auditOne(packageName: String): AppAuditResult? {
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or ComponentAnalyzer.PACKAGE_FLAGS)
        } catch (t: Throwable) {
            return null
        }
        return safeAudit(info)
    }

    private fun safeAudit(info: PackageInfo): AppAuditResult? = try {
        audit(info)
    } catch (t: Throwable) {
        null // 单个应用解析失败不应中断整轮扫描
    }

    private fun audit(info: PackageInfo): AppAuditResult {
        val appInfo: ApplicationInfo? = info.applicationInfo
        val declared = info.requestedPermissions?.toList().orEmpty()
        val appLabel = appInfo
            ?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: info.packageName

        val components = runCatching { ComponentAnalyzer.fromPackageInfo(info) }
            .getOrDefault(emptyList())
        val source = runCatching { signatures.inspect(info.packageName) }.getOrNull()

        val permScore = PermissionRules.scoreOf(declared)
        val allFindings = buildFindings(declared, components, source, appLabel, launcherOf(info.packageName))
        val score = compositeScore(permScore, allFindings)
        val level = RiskLevel.fromScore(score)

        return AppAuditResult(
            packageName = info.packageName,
            appLabel = appLabel,
            isSystem = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false,
            declaredPermissions = declared,
            score = score,
            level = level,
            findings = allFindings,
            components = components,
            source = source
        )
    }

    /**
     * 复合评分 = 权限分 + （组件 / 来源证据权重之和 × 0.5）。
     *
     * 折算系数与组合加成的 0.6 出于同一考虑：证据只做加成，不让单项直接顶格。
     * 权限分仍是主项——它回答"应用能做什么"，组件与来源只回答"暴露面有多大、从哪来"。
     */
    private fun compositeScore(permissionScore: Int, findings: List<Finding>): Int {
        val evidence = findings.filter {
            it.category == Finding.Category.COMPONENT || it.category == Finding.Category.SIGNATURE
        }
        val bonus = (evidence.sumOf { it.weight } * 0.5).toInt()
        return (permissionScore + bonus).coerceIn(0, 100)
    }

    private fun buildFindings(
        declared: List<String>,
        components: List<ComponentAnalyzer.ExportedComponent>,
        source: SignatureInspector.InstallSource?,
        appLabel: String,
        launcherActivity: String?
    ): List<Finding> {
        val out = mutableListOf<Finding>()

        // 1) 高危单权限逐条列出
        declared
            .filter { PermissionRules.weightOf(it) >= 60 }
            .sortedByDescending { PermissionRules.weightOf(it) }
            .forEach { perm ->
                out += Finding(
                    id = "perm.$perm",
                    title = readablePermission(perm),
                    detail = "该应用声明了这一高敏感权限，授予后能力范围较大",
                    level = RiskLevel.fromScore(PermissionRules.weightOf(perm)),
                    weight = PermissionRules.weightOf(perm),
                    category = Finding.Category.PERMISSION
                )
            }

        // 2) 命中的组合规则——这是判别力的核心
        PermissionRules.matchedCombos(declared).forEach { combo ->
            out += Finding(
                id = combo.id,
                title = "危险权限组合",
                detail = combo.reason,
                level = combo.level,
                weight = combo.bonus,
                category = Finding.Category.PERMISSION
            )
        }

        // 3) 组件暴露面：应用"真的提供了"哪些能力、哪些组件对外可见
        out += ComponentAnalyzer.findings(components, appLabel, launcherActivity)

        // 4) 安装来源与调试标志
        source?.let { out += signatures.findings(it, appLabel) }

        return out.sortedByDescending { it.weight }
    }

    /** 启动器 Activity 的完整类名，用于把"桌面图标"从暴露面统计里排除 */
    private fun launcherOf(packageName: String): String? = runCatching {
        pm.getLaunchIntentForPackage(packageName)?.component?.className
    }.getOrNull()

    /** 权限名 → 中文可读名。未收录的退回短名。 */
    private fun readablePermission(perm: String): String = when (perm) {
        "android.permission.BIND_ACCESSIBILITY_SERVICE" -> "无障碍服务（模拟操作）"
        "android.permission.BIND_DEVICE_ADMIN" -> "设备管理器（防卸载）"
        "android.permission.REQUEST_INSTALL_PACKAGES" -> "安装其他应用"
        "android.permission.READ_SMS" -> "读取短信"
        "android.permission.RECEIVE_SMS" -> "接收短信"
        "android.permission.PROCESS_OUTGOING_CALLS" -> "监听拨出电话"
        "android.permission.CALL_PHONE" -> "直接拨打电话"
        "android.permission.READ_CALL_LOG" -> "读取通话记录"
        "android.permission.SYSTEM_ALERT_WINDOW" -> "悬浮窗覆盖"
        "android.permission.WRITE_SETTINGS" -> "修改系统设置"
        "android.permission.READ_CONTACTS" -> "读取通讯录"
        "android.permission.WRITE_CONTACTS" -> "修改通讯录"
        "android.permission.READ_PHONE_STATE" -> "读取设备标识"
        "android.permission.READ_EXTERNAL_STORAGE" -> "读取存储"
        "android.permission.WRITE_EXTERNAL_STORAGE" -> "写入存储"
        "android.permission.ACCESS_FINE_LOCATION" -> "精确定位"
        "android.permission.ACCESS_BACKGROUND_LOCATION" -> "后台定位"
        "android.permission.RECORD_AUDIO" -> "录音"
        "android.permission.CAMERA" -> "使用摄像头"
        "android.permission.READ_CALENDAR" -> "读取日历"
        "android.permission.BODY_SENSORS" -> "身体传感器"
        "android.permission.QUERY_ALL_PACKAGES" -> "查询全部应用列表"
        "android.permission.PACKAGE_USAGE_STATS" -> "读取应用使用记录"
        "android.permission.INTERNET" -> "网络访问"
        "android.permission.FOREGROUND_SERVICE" -> "前台常驻服务"
        else -> perm.substringAfterLast('.')
    }
}
