package com.ice.guard

import com.ice.guard.core.monitor.InstallMonitor
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.core.scanner.PermissionAuditEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新装应用告警阈值测试。
 * 阈值行为的稳定性直接关系到"是否会骚扰用户"，必须锁死。
 */
class InstallMonitorTest {

    private fun result(score: Int): PermissionAuditEngine.AppAuditResult =
        PermissionAuditEngine.AppAuditResult(
            packageName = "com.example.app",
            appLabel = "示例应用",
            isSystem = false,
            declaredPermissions = listOf("android.permission.INTERNET"),
            score = score,
            level = RiskLevel.fromScore(score),
            findings = listOf(
                Finding(
                    id = "test",
                    title = "测试",
                    detail = "测试项",
                    level = RiskLevel.fromScore(score),
                    weight = score,
                    category = Finding.Category.PERMISSION
                )
            )
        )

    @Test
    fun `空结果不告警`() {
        assertFalse(InstallMonitor.shouldAlert(null))
    }

    @Test
    fun `低于阈值不告警`() {
        assertFalse(InstallMonitor.shouldAlert(result(InstallMonitor.ALERT_THRESHOLD - 1)))
    }

    @Test
    fun `达到阈值即告警`() {
        assertTrue(InstallMonitor.shouldAlert(result(InstallMonitor.ALERT_THRESHOLD)))
    }

    @Test
    fun `高于阈值必然告警`() {
        assertTrue(InstallMonitor.shouldAlert(result(100)))
    }

    @Test
    fun `阈值取在高级风险区间`() {
        // 阈值若低于 65 会频繁打扰用户，故锁定下界
        assertTrue(InstallMonitor.ALERT_THRESHOLD >= 65)
    }
}
