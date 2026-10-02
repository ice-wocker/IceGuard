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
 * 直接从 IceGuard 自身 debug APK 中取出的、经 AAPT2 编译与 manifest merger
 * 合并后的二进制清单（17940 字节，含 25+ 个组件）。
 * 期望值全部取自 `aapt2 dump xmltree` 的输出，作为权威对照基准：
 *
 * ```
 * aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk
 * unzip -o app-debug.apk AndroidManifest.xml -d <dir>   # 即为本夹具
 * ```
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
        assertEquals("真实样本大小应为 17940 字节", 17940, bytes.size)
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
        // 1.1.0 新增的守护相关权限也应被解析出来
        assertTrue(
            "应包含 RECEIVE_BOOT_COMPLETED，实际：${info.permissions}",
            info.permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED")
        )
        assertTrue(
            "应包含 PACKAGE_USAGE_STATS，实际：${info.permissions}",
            info.permissions.contains("android.permission.PACKAGE_USAGE_STATS")
        )
        assertFalse(
            "本应用不应申请 INTERNET 权限",
            info.permissions.contains("android.permission.INTERNET")
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

    // ———————————————————— 组件级解析（1.1.0 新增） ————————————————————

    @Test
    fun `应从真实清单中解析出组件`() {
        val info = AxmlParser.parseBytes(loadSample())
        assertTrue("解析应成功，实际错误：${info.error}", info.success)
        assertTrue(
            "应解析出足够多的组件，实际 ${info.components.size} 个",
            info.components.size >= 10
        )
        // 四类组件都应出现
        AxmlParser.ComponentType.entries.forEach { type ->
            assertTrue(
                "清单中应包含 $type 类型组件",
                info.components.any { it.type == type }
            )
        }
    }

    @Test
    fun `应识别导出的启动器 Activity 及其 MAIN action`() {
        val info = AxmlParser.parseBytes(loadSample())
        val main = info.components.firstOrNull { it.name == "com.ice.guard.ui.home.MainActivity" }
        assertNotNull("应解析到 MainActivity，实际：${info.components.map { it.name }}", main)

        assertEquals(AxmlParser.ComponentType.ACTIVITY, main!!.type)
        assertEquals("应显式声明 exported=true", true, main.exportedExplicit)
        assertTrue(
            "应解析出 MAIN action，实际：${main.actions}",
            main.actions.contains("android.intent.action.MAIN")
        )
        assertTrue("MainActivity 应判定为可导出", main.exported)
    }

    @Test
    fun `应识别显式关闭导出的 Activity`() {
        val info = AxmlParser.parseBytes(loadSample())
        val audit = info.components
            .firstOrNull { it.name == "com.ice.guard.ui.audit.AuditActivity" }
        assertNotNull("应解析到 AuditActivity", audit)

        assertEquals("应显式声明 exported=false", false, audit!!.exportedExplicit)
        assertFalse("显式 exported=false 不应被判为可导出", audit.exported)
    }

    @Test
    fun `应识别新装监听接收器及其声明的 action`() {
        val info = AxmlParser.parseBytes(loadSample())
        val receiver = info.components.firstOrNull {
            it.name == "com.ice.guard.core.monitor.InstallMonitorReceiver"
        }
        assertNotNull("应解析到 InstallMonitorReceiver", receiver)

        assertEquals(AxmlParser.ComponentType.RECEIVER, receiver!!.type)
        assertTrue(
            "应带 PACKAGE_ADDED，实际：${receiver.actions}",
            receiver.actions.contains("android.intent.action.PACKAGE_ADDED")
        )
        assertTrue(
            "应带 PACKAGE_REPLACED，实际：${receiver.actions}",
            receiver.actions.contains("android.intent.action.PACKAGE_REPLACED")
        )
        assertTrue("该接收器应判定为可导出", receiver.exported)
    }

    @Test
    fun `应识别开机自检接收器`() {
        val info = AxmlParser.parseBytes(loadSample())
        val boot = info.components.firstOrNull {
            it.name == "com.ice.guard.core.guard.BootGuardReceiver"
        }
        assertNotNull("应解析到 BootGuardReceiver", boot)
        assertTrue(
            "应带 BOOT_COMPLETED，实际：${boot!!.actions}",
            boot.actions.contains("android.intent.action.BOOT_COMPLETED")
        )
    }

    @Test
    fun `应解析出无障碍服务的绑定权限`() {
        val info = AxmlParser.parseBytes(loadSample())
        val a11y = info.components.firstOrNull {
            it.name == "com.ice.guard.core.guard.GuardAccessibilityService"
        }
        assertNotNull("应解析到无障碍服务", a11y)
        assertEquals(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            a11y!!.permission
        )
        assertFalse(
            "带 BIND_ACCESSIBILITY_SERVICE 的组件不应被视为无保护暴露",
            a11y.unprotected
        )
    }

    @Test
    fun `应识别 Provider 组件并区分是否受权限保护`() {
        val info = AxmlParser.parseBytes(loadSample())
        val providers = info.components.filter { it.type == AxmlParser.ComponentType.PROVIDER }
        assertTrue(
            "应解析出 Provider，实际组件：${info.components.map { it.name }}",
            providers.isNotEmpty()
        )

        // ShizukuProvider 声明了 INTERACT_ACROSS_USERS_FULL，属于受保护组件
        val shizuku = providers.firstOrNull { it.name.contains("ShizukuProvider") }
        assertNotNull("应解析到 ShizukuProvider", shizuku)
        assertEquals(
            "android.permission.INTERACT_ACROSS_USERS_FULL",
            shizuku!!.permission
        )
        assertFalse("受权限保护的 Provider 不应算暴露", shizuku.unprotected)
    }

    @Test
    fun `每个组件名都应有明确的类型归属`() {
        val info = AxmlParser.parseBytes(loadSample())
        info.components.forEach { c ->
            assertTrue("组件名不应为空", c.name.isNotBlank())
            assertTrue(
                "组件类型应属于四类之一",
                AxmlParser.ComponentType.entries.contains(c.type)
            )
        }
    }

    @Test
    fun `未声明 exported 时按有无 intent-filter 推断`() {
        // 行为约定：未显式声明时，按 Android 12 之前的系统默认——
        // 带 intent-filter 的组件默认可导出
        val withFilter = AxmlParser.ComponentInfo(
            AxmlParser.ComponentType.SERVICE, ".S1", null, null, listOf("com.x.ACTION")
        )
        val withoutFilter = AxmlParser.ComponentInfo(
            AxmlParser.ComponentType.SERVICE, ".S2", null, null, emptyList()
        )
        assertTrue(withFilter.exported)
        assertFalse(withoutFilter.exported)
        assertTrue("带 intent-filter 且无权限保护应视为暴露", withFilter.unprotected)
    }
}
