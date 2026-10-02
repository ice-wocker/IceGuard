package com.ice.guard

import com.ice.guard.core.guard.AutoResponder
import com.ice.guard.core.guard.GuardPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护决策逻辑的单元测试。
 *
 * `AutoResponder.decide` 是整条响应链路的大脑，被刻意抽成纯函数，
 * 就是为了让「什么情况下会动手」这件事可以被测试锁死——
 * 自动处置一旦误触发，是会真的动用户应用的，必须可验证。
 */
class AutoResponderTest {

    private val defaultPolicy = GuardPolicy()

    // ———————————————————— 阈值 ————————————————————

    @Test
    fun `未达阈值时不做任何事`() {
        assertEquals(
            AutoResponder.Decision.IGNORE,
            AutoResponder.decide(defaultPolicy.threshold - 1, defaultPolicy, shizukuReady = true)
        )
    }

    @Test
    fun `达到阈值即进入关注区间`() {
        assertEquals(
            AutoResponder.Decision.ALERT_ONLY,
            AutoResponder.decide(defaultPolicy.threshold, defaultPolicy, shizukuReady = true)
        )
    }

    @Test
    fun `默认策略是只告警不动手`() {
        assertFalse("自动处置必须默认关闭", defaultPolicy.autoRespond)
        assertEquals(
            AutoResponder.Decision.ALERT_ONLY,
            AutoResponder.decide(100, defaultPolicy, shizukuReady = true)
        )
    }

    // ———————————————————— 授权与终止 ————————————————————

    @Test
    fun `开启自动处置但未授权 Shizuku 时仍只能告警`() {
        val policy = defaultPolicy.copy(autoRespond = true)
        assertEquals(
            AutoResponder.Decision.ALERT_ONLY,
            AutoResponder.decide(90, policy, shizukuReady = false)
        )
    }

    @Test
    fun `开启自动处置且已授权时终止并冻结`() {
        val policy = defaultPolicy.copy(autoRespond = true)
        assertEquals(
            AutoResponder.Decision.TERMINATE_AND_FREEZE,
            AutoResponder.decide(90, policy, shizukuReady = true)
        )
    }

    @Test
    fun `关闭冻结开关时只终止不冻结`() {
        val policy = defaultPolicy.copy(autoRespond = true, freeze = false)
        assertEquals(
            AutoResponder.Decision.TERMINATE_ONLY,
            AutoResponder.decide(90, policy, shizukuReady = true)
        )
    }

    // ———————————————————— 用户主动处置 ————————————————————

    @Test
    fun `用户主动处置不受阈值限制`() {
        assertEquals(
            AutoResponder.Decision.TERMINATE_AND_FREEZE,
            AutoResponder.decide(10, defaultPolicy, shizukuReady = true, forced = true)
        )
    }

    @Test
    fun `用户主动处置但未授权时只能告警`() {
        assertEquals(
            AutoResponder.Decision.ALERT_ONLY,
            AutoResponder.decide(90, defaultPolicy, shizukuReady = false, forced = true)
        )
    }

    // ———————————————————— 阈值配置 ————————————————————

    @Test
    fun `阈值应落在允许范围内且默认值不低于 65`() {
        assertTrue("默认阈值过高会漏报", GuardPolicy.DEFAULT_THRESHOLD >= 65)
        assertTrue(GuardPolicy.THRESHOLD_RANGE.contains(GuardPolicy.DEFAULT_THRESHOLD))
        assertTrue("阈值为 0 等于对所有应用动手", GuardPolicy.THRESHOLD_RANGE.first >= 40)
    }

    @Test
    fun `自定义阈值应改变判定边界`() {
        val strict = defaultPolicy.copy(threshold = 40)
        assertEquals(
            AutoResponder.Decision.ALERT_ONLY,
            AutoResponder.decide(40, strict, shizukuReady = true)
        )
        assertEquals(
            AutoResponder.Decision.IGNORE,
            AutoResponder.decide(39, strict, shizukuReady = true)
        )
    }
}