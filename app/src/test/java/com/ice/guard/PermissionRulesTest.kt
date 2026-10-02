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

    // ———————————————————— 扩充规则（1.1.0 新增 9 条） ————————————————————

    @Test
    fun `短信加无障碍应命中无需联网的盗刷组合`() {
        val perms = listOf(
            "android.permission.READ_SMS",
            "android.permission.BIND_ACCESSIBILITY_SERVICE"
        )
        val combos = PermissionRules.matchedCombos(perms)
        assertTrue("应命中 combo.sms_a11y", combos.any { it.id == "combo.sms_a11y" })
        assertEquals(
            "读取验证码 + 代点确认应顶格为极高风险",
            RiskLevel.CRITICAL,
            RiskLevel.fromScore(PermissionRules.scoreOf(perms))
        )
    }

    @Test
    fun `联网加安装应用应命中投放组合`() {
        val perms = listOf(
            "android.permission.INTERNET",
            "android.permission.REQUEST_INSTALL_PACKAGES"
        )
        assertTrue(
            PermissionRules.matchedCombos(perms).any { it.id == "combo.install_net" }
        )
    }

    @Test
    fun `设备管理器加安装应用应判为极高风险`() {
        val perms = listOf(
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.REQUEST_INSTALL_PACKAGES"
        )
        assertTrue(
            PermissionRules.matchedCombos(perms).any { it.id == "combo.admin_install" }
        )
        assertEquals(
            RiskLevel.CRITICAL,
            RiskLevel.fromScore(PermissionRules.scoreOf(perms))
        )
    }

    @Test
    fun `录音加摄像头加后台定位应命中本地窃听组合`() {
        val perms = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )
        assertTrue(
            "不依赖网络也应提示窃听窃视风险",
            PermissionRules.matchedCombos(perms).any { it.id == "combo.spy_local" }
        )
    }

    @Test
    fun `扩规则后普通应用仍不应越过高风险线`() {
        // 一个典型的内容类应用：联网、相机、存储、通知，没有短信/通讯录/无障碍
        val typical = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.VIBRATE",
            "android.permission.WAKE_LOCK"
        )
        val score = PermissionRules.scoreOf(typical)
        assertTrue("常见应用不应被判为高风险，实际 $score", score < 65)
    }

    @Test
    fun `规则 id 不应重复`() {
        val ids = PermissionRules.COMBO_RULES.map { it.id }
        assertEquals("组合规则 id 必须唯一", ids.size, ids.distinct().size)
    }

    @Test
    fun `每条组合规则都应给出可读的判定理由`() {
        PermissionRules.COMBO_RULES.forEach { rule ->
            assertTrue("规则 ${rule.id} 缺少理由", rule.reason.isNotBlank())
            assertTrue("规则 ${rule.id} 的条件不应为空", rule.requires.isNotEmpty())
            assertTrue("规则 ${rule.id} 的加成应为正数", rule.bonus > 0)
        }
    }
}
