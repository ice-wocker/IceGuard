package com.ice.guard

import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.core.scanner.AxmlParser
import com.ice.guard.core.scanner.ComponentAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件暴露面分析的单元测试。
 *
 * 这组用例的重点是**误报控制**：组件分析天生容易刷屏
 * （每个应用都有导出的启动器 Activity、都有带 intent-filter 的组件），
 * 因此测试锁死的是"什么不该报"，而不只是"什么该报"。
 */
class ComponentAnalyzerTest {

    private fun component(
        type: AxmlParser.ComponentType,
        name: String,
        exported: Boolean,
        permission: String?,
        actions: List<String> = emptyList()
    ) = ComponentAnalyzer.ExportedComponent(type, name, exported, permission, actions)

    private val mainAction = "android.intent.action.MAIN"

    // ———————————————————— 不该报的 ————————————————————

    @Test
    fun `启动器 Activity 不应被计入对外暴露`() {
        val launcher = component(
            AxmlParser.ComponentType.ACTIVITY, ".ui.Main", exported = true,
            permission = null, actions = listOf(mainAction)
        )
        val findings = ComponentAnalyzer.findings(listOf(launcher), "正常应用")
        assertTrue(
            "桌面入口天然导出，不应报为暴露面：$findings",
            findings.none { it.id == "component.exported.activity" }
        )
    }

    @Test
    fun `通过 launcherActivity 参数显式排除启动器`() {
        // 已安装应用路径拿不到 intent-filter，只能靠 PackageManager 查出的类名排除
        val launcher = component(
            AxmlParser.ComponentType.ACTIVITY, ".ui.Main", exported = true, permission = null
        )
        val findings = ComponentAnalyzer.findings(
            listOf(launcher), "正常应用", launcherActivity = ".ui.Main"
        )
        assertTrue(findings.none { it.id == "component.exported.activity" })
    }

    @Test
    fun `受权限保护的导出组件不应报为暴露面`() {
        val guarded = component(
            AxmlParser.ComponentType.SERVICE, ".SyncService", exported = true,
            permission = "com.example.permission.CALL_SYNC"
        )
        val findings = ComponentAnalyzer.findings(listOf(guarded), "正常应用")
        assertTrue(findings.none { it.id == "component.exported.service" })
    }

    @Test
    fun `未导出的组件不应报为暴露面`() {
        val internal = component(
            AxmlParser.ComponentType.PROVIDER, ".InnerProvider", exported = false, permission = null
        )
        val findings = ComponentAnalyzer.findings(listOf(internal), "正常应用")
        assertTrue(findings.none { it.id == "component.exported.provider" })
    }

    @Test
    fun `没有任何组件的应用不应产出发现项`() {
        assertTrue(ComponentAnalyzer.findings(emptyList(), "空应用").isEmpty())
    }

    // ———————————————————— 该报的 ————————————————————

    @Test
    fun `无保护的导出 ContentProvider 应判为高风险`() {
        val provider = component(
            AxmlParser.ComponentType.PROVIDER, ".DataProvider", exported = true, permission = null
        )
        val findings = ComponentAnalyzer.findings(listOf(provider), "可疑应用")
        val hit = findings.firstOrNull { it.id == "component.exported.provider" }
        assertTrue("应命中 Provider 暴露", hit != null)
        assertTrue("Provider 数据暴露应为高风险", hit!!.level >= RiskLevel.HIGH)
    }

    @Test
    fun `无保护的导出 Service 与 Receiver 应被分别统计`() {
        val list = listOf(
            component(AxmlParser.ComponentType.SERVICE, ".A", exported = true, permission = null),
            component(AxmlParser.ComponentType.SERVICE, ".B", exported = true, permission = null),
            component(AxmlParser.ComponentType.RECEIVER, ".C", exported = true, permission = null)
        )
        val findings = ComponentAnalyzer.findings(list, "可疑应用")
        assertTrue(findings.any { it.id == "component.exported.service" && it.title.contains("2") })
        assertTrue(findings.any { it.id == "component.exported.receiver" && it.title.contains("1") })
    }

    // ———————————————————— 绑定服务（能力宣告） ————————————————————

    @Test
    fun `声明无障碍绑定权限的服务应被识别为内置无障碍能力`() {
        val a11y = component(
            AxmlParser.ComponentType.SERVICE, ".A11yService", exported = false,
            permission = "android.permission.BIND_ACCESSIBILITY_SERVICE"
        )
        val findings = ComponentAnalyzer.findings(listOf(a11y), "可疑应用")
        val hit = findings.firstOrNull {
            it.id == "component.binding.android.permission.BIND_ACCESSIBILITY_SERVICE"
        }
        assertTrue("应识别出无障碍服务", hit != null)
        assertTrue("无障碍服务应判为高风险", hit!!.level >= RiskLevel.HIGH)
    }

    @Test
    fun `声明通知读取与 VPN 绑定权限的服务都应被识别`() {
        val list = listOf(
            component(
                AxmlParser.ComponentType.SERVICE, ".NotifyListener", exported = false,
                permission = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
            ),
            component(
                AxmlParser.ComponentType.SERVICE, ".Vpn", exported = false,
                permission = "android.permission.BIND_VPN_SERVICE"
            )
        )
        val findings = ComponentAnalyzer.findings(list, "可疑应用")
        assertTrue(findings.any { it.id.contains("BIND_NOTIFICATION_LISTENER_SERVICE") })
        assertTrue(findings.any { it.id.contains("BIND_VPN_SERVICE") })
    }

    @Test
    fun `同一种绑定能力只报一次`() {
        val list = listOf(
            component(
                AxmlParser.ComponentType.SERVICE, ".A11y1", exported = false,
                permission = "android.permission.BIND_ACCESSIBILITY_SERVICE"
            ),
            component(
                AxmlParser.ComponentType.SERVICE, ".A11y2", exported = false,
                permission = "android.permission.BIND_ACCESSIBILITY_SERVICE"
            )
        )
        val findings = ComponentAnalyzer.findings(list, "可疑应用")
            .filter { it.id.contains("BIND_ACCESSIBILITY_SERVICE") }
        assertTrue("两个无障碍服务项应合并为一条，实际 ${findings.size} 条", findings.size == 1)
    }

    // ———————————————————— 清单来源 ————————————————————

    @Test
    fun `从 AXML 清单构造的组件应保留 intent-filter 信息`() {
        val manifest = AxmlParser.ManifestInfo(
            packageName = "com.example",
            permissions = emptyList(),
            success = true,
            components = listOf(
                AxmlParser.ComponentInfo(
                    type = AxmlParser.ComponentType.ACTIVITY,
                    name = ".Main",
                    exportedExplicit = true,
                    permission = null,
                    actions = listOf(mainAction)
                )
            )
        )
        val converted = ComponentAnalyzer.fromManifest(manifest)
        assertTrue(converted.size == 1)
        assertTrue("应识别为启动器入口", converted.first().isLauncher)
        assertTrue(ComponentAnalyzer.findings(converted, "样包").isEmpty())
    }
}