package com.ice.guard

import com.ice.guard.core.privilege.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShellResult 的语义测试。
 * 核心诉求：成败判定只看 exitCode，不因空输出误判。
 */
class ShellResultTest {

    @Test
    fun `exitCode 0 视为成功`() {
        val r = ShellResult("pm list packages", 0, "package:a", "", true)
        assertTrue(r.ok)
    }

    @Test
    fun `非零 exitCode 视为失败_即使有输出`() {
        val r = ShellResult("pm uninstall x", 1, "", "Failure [not installed]", true)
        assertFalse(r.ok)
    }

    @Test
    fun `失败摘要包含 stderr`() {
        val r = ShellResult("pm disable-user x", 255, "", "Error: java.lang.SecurityException", true)
        assertTrue(r.summary().contains("SecurityException"))
    }

    @Test
    fun `失败但 stderr 为空时回退到 stdout`() {
        val r = ShellResult("pm clear x", 1, "some output", "", false)
        assertTrue(r.summary().contains("some output"))
    }

    @Test
    fun `超时以负的 exitCode 编码`() {
        val r = ShellResult("pm list packages", -2, "", "命令超时（15s）", true)
        assertFalse(r.ok)
        assertEquals(-2, r.exitCode)
    }
}
