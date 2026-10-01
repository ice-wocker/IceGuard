package com.ice.guard

import com.ice.guard.core.rules.PermissionRules
import com.ice.guard.core.rules.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限风险评估算法的单元测试。
 * 这些用例覆盖算法设计意图，是验证评分模型是否合理的主要手段。
 */
class PermissionRulesTest {

    @Test
    fun `无权限时应为零分`() {
        assertEquals(0, PermissionRules.scoreOf(emptyList()))
    }

    @Test
    fun `单一普通权限不应触发高危`() {
        val score = PermissionRules.scoreOf(listOf("android.permission.INTERNET"))
        assertTrue("仅联网权限应处于安全或低风险区间，实际 $score", score < 40)
    }

    @Test
    fun `短信加联网应命中高危组合`() {
        val perms = listOf(
            "android.permission.READ_SMS",
            "android.permission.INTERNET"
        )
        val combos = PermissionRules.matchedCombos(perms)
        assertTrue("应命中 sms_net 组合", combos.any { it.id == "combo.sms_net" })
        assertTrue("评分应达到高风险及以上", PermissionRules.scoreOf(perms) >= 65)
    }

    @Test
    fun `无障碍加短信加联网应判定为极高风险`() {
        val perms = listOf(
            "android.permission.RECEIVE_SMS",
            "android.permission.INTERNET",
            "android.permission.BIND_ACCESSIBILITY_SERVICE"
        )
        val score = PermissionRules.scoreOf(perms)
        assertEquals("该组合应顶格为极高风险", RiskLevel.CRITICAL, RiskLevel.fromScore(score))
    }

    @Test
    fun `安装应用加悬浮窗应命中组合`() {
        val perms = listOf(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SYSTEM_ALERT_WINDOW"
        )
        val combos = PermissionRules.matchedCombos(perms)
        assertTrue(combos.any { it.id == "combo.install_overlay" })
    }

    @Test
    fun `评分不应超出100`() {
        val all = PermissionRules.PERMISSION_WEIGHTS.keys.toList()
        val score = PermissionRules.scoreOf(all)
        assertTrue("评分为 $score，应在 0-100 之间", score in 0..100)
    }

    @Test
    fun `权限更多的正常应用不应必然被判为高危`() {
        // 一个典型社交应用的权限组合：联网 + 相机 + 麦克风 + 存储 + 通知
        val typical = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.VIBRATE",
            "android.permission.WAKE_LOCK"
        )
        val score = PermissionRules.scoreOf(typical)
        assertTrue(
            "常见应用权限组合不应直接判为极高风险，实际 $score",
            score < 100
        )
    }

    @Test
    fun `风险等级阈值应单调递增`() {
        assertEquals(RiskLevel.SAFE, RiskLevel.fromScore(0))
        assertEquals(RiskLevel.LOW, RiskLevel.fromScore(30))
        assertEquals(RiskLevel.MEDIUM, RiskLevel.fromScore(50))
        assertEquals(RiskLevel.HIGH, RiskLevel.fromScore(70))
        assertEquals(RiskLevel.CRITICAL, RiskLevel.fromScore(90))
    }

    @Test
    fun `未追踪权限不应影响评分`() {
        val score = PermissionRules.scoreOf(listOf("com.example.custom.PERMISSION"))
        assertEquals(0, score)
    }
}
