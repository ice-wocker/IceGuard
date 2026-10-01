package com.ice.guard.core.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ice.guard.core.report.ScanReport
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.di.AppContainer

/**
 * 体检编排器：串行调度各模块，汇总为一份 ScanReport。
 *
 * 评分模型（自研）：
 *   综合分 = 100 - 各模块风险扣分之和（下限 0）
 *   模块扣分 = 该模块内发现项权重的加权汇总，单模块扣分上限 40，
 *   避免单一模块的密集告警把总分一次性压到底，导致评分失去区分度。
 */
class HealthCheckCoordinator {

    /** 各模块完成时回调，用于 UI 实时展示进度 */
    data class Progress(val step: Int, val total: Int, val label: String)

    suspend fun runFullCheck(
        includeApk: Boolean = true,
        onProgress: (Progress) -> Unit = {}
    ): ScanReport = withContext(Dispatchers.IO) {
        val modules = mutableListOf<ScanReport.ModuleResult>()

        val totalSteps = if (includeApk) 3 else 2
        var step = 0

        // —— 模块 1：应用权限审计 ——
        onProgress(Progress(++step, totalSteps, "正在审计应用权限"))
        modules += runPermissionModule()

        // —— 模块 2：设备环境检测（模块 3 的引擎，此处并入体检流程）——
        onProgress(Progress(++step, totalSteps, "正在检测设备环境"))
        modules += runEnvironmentModule()

        // —— 模块 3：APK 静态扫描（耗时，可选）——
        if (includeApk) {
            onProgress(Progress(++step, totalSteps, "正在扫描存储中的安装包"))
            modules += runApkModule()
        }

        val overall = computeOverallScore(modules)
        ScanReport(
            timestamp = System.currentTimeMillis(),
            overallScore = overall,
            modules = modules
        )
    }

    private fun runPermissionModule(): ScanReport.ModuleResult {
        val engine = AppContainer.permissionAudit
        val results = engine.auditAll(includeSystem = false)

        // 只把「中风险及以上」的应用汇入发现项，避免正常应用刷屏
        val findings = results
            .filter { it.level >= RiskLevel.MEDIUM }
            .map { app ->
                com.ice.guard.core.rules.Finding(
                    id = "app.${app.packageName}",
                    title = app.appLabel,
                    detail = buildAppDetail(app),
                    level = app.level,
                    weight = app.score,
                    category = com.ice.guard.core.rules.Finding.Category.PERMISSION
                )
            }

        return ScanReport.ModuleResult(
            moduleId = "module.permission",
            moduleName = "应用权限审计",
            score = computeModuleScore(findings),
            findings = findings
        )
    }

    private fun buildAppDetail(app: PermissionAuditEngine.AppAuditResult): String {
        val combos = app.findings.filter { it.id.startsWith("combo.") }
        val base = "风险分 ${app.score}，声明权限 ${app.declaredPermissions.size} 项"
        return if (combos.isEmpty()) {
            base
        } else {
            base + "；命中组合：" + combos.joinToString("；") { it.detail }
        }
    }

    private fun runEnvironmentModule(): ScanReport.ModuleResult {
        val engine = AppContainer.environmentCheck
        val findings = engine.check()
        return ScanReport.ModuleResult(
            moduleId = "module.environment",
            moduleName = "设备环境检测",
            score = computeModuleScore(findings),
            findings = findings
        )
    }

    private fun runApkModule(): ScanReport.ModuleResult {
        val engine = AppContainer.apkScan
        val findings = engine.scanCommonDirs()
        return ScanReport.ModuleResult(
            moduleId = "module.apk",
            moduleName = "安装包静态扫描",
            score = computeModuleScore(findings),
            findings = findings
        )
    }

    /**
     * 模块得分：满分 100，按发现项权重扣分，单模块扣分上限 40。
     */
    private fun computeModuleScore(findings: List<com.ice.guard.core.rules.Finding>): Int {
        if (findings.isEmpty()) return 100
        val deduction = findings.sumOf { it.weight } / 10
        return (100 - deduction.coerceAtMost(40)).coerceIn(0, 100)
    }

    /**
     * 综合得分。权重分配：权限审计 50%、环境检测 30%、APK 扫描 20%。
     * 若某模块未执行，其权重按比例分摊到其余模块。
     */
    private fun computeOverallScore(modules: List<ScanReport.ModuleResult>): Int {
        if (modules.isEmpty()) return 100
        val weights = mapOf(
            "module.permission" to 0.50,
            "module.environment" to 0.30,
            "module.apk" to 0.20
        )
        val active = modules.filter { weights.containsKey(it.moduleId) }
        val totalWeight = active.sumOf { weights[it.moduleId] ?: 0.0 }
        if (totalWeight <= 0.0) {
            return active.map { it.score }.average().toInt().coerceIn(0, 100)
        }
        val weighted = active.sumOf { (weights[it.moduleId] ?: 0.0) * it.score } / totalWeight
        return weighted.toInt().coerceIn(0, 100)
    }
}
