package com.ice.guard

import com.ice.guard.core.scanner.AxmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AXML 解析器测试。
 *
 * 重要说明：本测试使用的 `real_manifest.axml` 是真实样本——
 * 由 AAPT2 编译 IceGuard 自身产生的二进制清单文件（6228 字节）。
 * 期望值取自 `aapt2 dump xmltree` 的输出，作为权威对照基准。
 *
 * 这组用例的目的是确保解析器在真实 AAPT2 输出上可用，
 * 而非仅通过手工构造的理想数据。
 */
class AxmlParserTest {

    private fun loadSample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("real_manifest.axml")!!.readBytes()

    @Test
    fun `样本文件应能加载且大小正确`() {
        val bytes = loadSample()
        assertEquals("真实样本大小应为 6228 字节", 6228, bytes.size)
        // 校验文件头为 RES_XML_TYPE
        assertEquals("头部类型应为 0x0003", 0x03, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `应从真实清单中解析出包名`() {
        val info = AxmlParser.parseBytes(loadSample())
        assertTrue("解析应成功，实际错误：${info.error}", info.success)
        assertEquals("com.ice.guard", info.packageName)
    }

    @Test
    fun `应从真实清单中解析出全部权限`() {
        val info = AxmlParser.parseBytes(loadSample())
        assertTrue(info.success)

        // 与 aapt2 dump permissions 的输出对照
        assertTrue(
            "应包含 QUERY_ALL_PACKAGES，实际：${info.permissions}",
            info.permissions.contains("android.permission.QUERY_ALL_PACKAGES")
        )
        assertTrue(
            "应包含 READ_EXTERNAL_STORAGE，实际：${info.permissions}",
            info.permissions.contains("android.permission.READ_EXTERNAL_STORAGE")
        )
    }

    @Test
    fun `权限列表不应包含重复项`() {
        val info = AxmlParser.parseBytes(loadSample())
        assertEquals(
            "权限列表应已去重",
            info.permissions.size,
            info.permissions.distinct().size
        )
    }

    @Test
    fun `解析出的权限应全部是非空字符串`() {
        val info = AxmlParser.parseBytes(loadSample())
        info.permissions.forEach { p ->
            assertTrue("权限名不应为空：'$p'", p.isNotBlank())
            assertTrue("权限名应形如 android.permission.X：'$p'", p.contains('.'))
        }
    }

    @Test
    fun `解析结果应可用于风险评分`() {
        val info = AxmlParser.parseBytes(loadSample())
        val score = com.ice.guard.core.rules.PermissionRules.scoreOf(info.permissions)
        // IceGuard 自身只声明了两个权限，风险分不应很高
        assertTrue("自身权限组合风险分应较低，实际 $score", score < 40)
    }

    // ———————————————————— 健壮性测试 ————————————————————

    @Test
    fun `空数据应返回失败而非崩溃`() {
        val info = AxmlParser.parseBytes(ByteArray(0))
        assertFalse(info.success)
        assertNotNull(info.error)
    }

    @Test
    fun `过短数据应返回失败而非崩溃`() {
        val info = AxmlParser.parseBytes(byteArrayOf(0x03, 0x00, 0x08, 0x00))
        assertFalse(info.success)
    }

    @Test
    fun `非 AXML 数据应返回失败而非崩溃`() {
        // 构造一个类型码错误的头
        val bad = ByteArray(64)
        bad[0] = 0x50; bad[1] = 0x4B // "PK" —— 这是 ZIP 头，不是 AXML
        val info = AxmlParser.parseBytes(bad)
        assertFalse(info.success)
        assertTrue("错误信息应说明类型不符", info.error?.contains("二进制") == true)
    }

    @Test
    fun `截断的真实样本不应导致崩溃`() {
        val full = loadSample()
        // 逐段截断，验证在任何长度下都不抛异常
        listOf(16, 64, 256, 1024, 3000, 6000).forEach { len ->
            val truncated = full.copyOfRange(0, len)
            val info = AxmlParser.parseBytes(truncated)
            // 只要求不崩溃；能否解析取决于截断位置
            assertNotNull("长度 $len 时不应返回 null", info)
        }
    }

    @Test
    fun `字节翻转的样本不应导致崩溃`() {
        val corrupted = loadSample().copyOf()
        // 破坏字符串池区域的部分字节
        for (i in 100 until minOf(300, corrupted.size)) {
            corrupted[i] = (corrupted[i].toInt() xor 0xFF).toByte()
        }
        val info = AxmlParser.parseBytes(corrupted)
        assertNotNull(info)
    }

    @Test
    fun `从输入流解析应与字节数组解析结果一致`() {
        val bytes = loadSample()
        val fromBytes = AxmlParser.parseBytes(bytes)
        val fromStream = AxmlParser.parse(java.io.ByteArrayInputStream(bytes))

        assertEquals(fromBytes.packageName, fromStream.packageName)
        assertEquals(fromBytes.permissions, fromStream.permissions)
        assertEquals(fromBytes.success, fromStream.success)
    }

    @Test
    fun `不存在的 APK 文件应返回失败而非崩溃`() {
        val info = AxmlParser.parseFromApk(java.io.File("/nonexistent/path/fake.apk"))
        assertFalse(info.success)
        assertNotNull(info.error)
    }
}
