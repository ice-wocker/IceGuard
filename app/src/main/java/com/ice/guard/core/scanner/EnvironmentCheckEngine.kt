package com.ice.guard.core.scanner

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel
import java.io.File

/**
 * 模块 3 · 设备环境检测引擎。
 *
 * 检测项：Root 痕迹（多策略交叉）、危险开发者设置、无障碍服务启用情况、
 * 设备管理器绑定、存储加密状态、锁屏强度。
 *
 * 能力边界（诚实声明）：
 *  - Root 检测为启发式判断，无法 100% 确证，存在误报与漏报的可能。
 *  - 「检测到 Root」本身不等于「设备不安全」，而是提示用户注意风险面扩大。
 */
class EnvironmentCheckEngine(private val context: Context) {

    fun check(): List<Finding> {
        val findings = mutableListOf<Finding>()
        findings += checkRoot()
        findings += checkDeveloperOptions()
        findings += checkAccessibilityServices()
        findings += checkDeviceAdmins()
        findings += checkUnknownSources()
        findings += checkStorageEncryption()
        findings += checkScreenLock()
        return findings.sortedByDescending { it.weight }
    }

    // ———————————————————— Root 检测 ————————————————————

    /**
     * 多策略交叉检测 Root。任一策略命中即计入证据，
     * 依据命中数量给出置信度，避免单一特征导致误判。
     */
    private fun checkRoot(): List<Finding> {
        val evidence = mutableListOf<String>()

        // 策略 1：常见 su 二进制路径
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/data/local/bin/su", "/data/local/xbin/su", "/magisk/.core/bin/su"
        )
        suPaths.firstOrNull { File(it).exists() }?.let { evidence += "存在 su 可执行文件：$it" }

        // 策略 2：build 标签（userdebug / test-keys 为工程机特征）
        val tags = Build.TAGS.orEmpty()
        if (tags.contains("test-keys")) evidence += "系统构建标签包含 test-keys"

        // 策略 3：常见 Root 管理应用
        val rootPackages = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "me.weishu.kernelsu"
        )
        val installed = rootPackages.filter { isPackageInstalled(it) }
        if (installed.isNotEmpty()) evidence += "检测到 Root 管理应用：${installed.joinToString()}"

        // 策略 4：可写系统目录
        val systemWritable = listOf("/system", "/system/bin", "/system/xbin")
            .filter { File(it).canWrite() }
        if (systemWritable.isNotEmpty()) evidence += "系统目录可写：${systemWritable.joinToString()}"

        if (evidence.isEmpty()) return emptyList()

        val confidence = when (evidence.size) {
            1 -> "较低（可能为误报）"
            2 -> "中等"
            else -> "较高"
        }

        return listOf(
            Finding(
                id = "env.root",
                title = "设备疑似已 Root",
                detail = "命中 ${evidence.size} 项特征，置信度 $confidence。证据：" +
                    evidence.joinToString("；") +
                    "。Root 后应用的权限边界失效，正常应用也可能获得系统级能力。",
                level = if (evidence.size >= 2) RiskLevel.HIGH else RiskLevel.MEDIUM,
                weight = if (evidence.size >= 2) 70 else 40,
                category = Finding.Category.ENVIRONMENT
            )
        )
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    // ———————————————————— 开发者选项 ————————————————————

    private fun checkDeveloperOptions(): List<Finding> {
        val out = mutableListOf<Finding>()

        // ADB 调试开关
        val adbEnabled = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        ) == 1
        if (adbEnabled) {
            out += Finding(
                id = "env.adb",
                title = "USB 调试已开启",
                detail = "开启后，连接电脑即可通过 ADB 读取数据、安装应用。在公共充电桩等场景下容易被利用，建议日常关闭。",
                level = RiskLevel.MEDIUM,
                weight = 45,
                category = Finding.Category.ENVIRONMENT
            )
        }

        // 模拟位置（通常需开发者选项）
        val mockLocation = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ALLOW_MOCK_LOCATION
            ) == "1"
        } catch (t: Throwable) {
            false
        }
        if (mockLocation) {
            out += Finding(
                id = "env.mock_location",
                title = "允许模拟位置",
                detail = "应用可伪造定位信息，可能影响打卡、导航、位置类应用的可信度。",
                level = RiskLevel.LOW,
                weight = 25,
                category = Finding.Category.ENVIRONMENT
            )
        }

        return out
    }

    // ———————————————————— 无障碍服务 ————————————————————

    /**
     * 检查哪些应用启用了无障碍服务。
     * 无障碍是高危权限，正常应用极少需要，因此单独列出授予情况。
     */
    private fun checkAccessibilityServices(): List<Finding> {
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (t: Throwable) {
            ""
        }

        if (enabled.isBlank()) return emptyList()

        val services = enabled.split(':')
            .mapNotNull { it.trim().substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .distinct()

        if (services.isEmpty()) return emptyList()

        val names = services.map { pkg ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(pkg)
        }

        return listOf(
            Finding(
                id = "env.accessibility",
                title = "有 ${services.size} 个应用启用了无障碍服务",
                detail = "启用无障碍的应用可以读取屏幕内容、模拟点击操作。请确认以下应用均为你主动启用的可信应用：" +
                    names.joinToString("、") +
                    "。若不认识其中某个应用，建议前往系统设置关闭其无障碍权限。",
                level = if (services.size >= 3) RiskLevel.HIGH else RiskLevel.MEDIUM,
                weight = if (services.size >= 3) 65 else 45,
                category = Finding.Category.ENVIRONMENT
            )
        )
    }

    // ———————————————————— 设备管理器 ————————————————————

    private fun checkDeviceAdmins(): List<Finding> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyList()
        val admins = try {
            dpm.activeAdmins.orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
        if (admins.isEmpty()) return emptyList()

        val names = admins.map { it.packageName }.distinct()
        return listOf(
            Finding(
                id = "env.device_admin",
                title = "有 ${names.size} 个应用持有设备管理器权限",
                detail = "持有该权限的应用通常无法直接卸载，并可能具备锁屏、清除数据的能力。" +
                    "涉及应用：" + names.joinToString("、") +
                    "。请确认均为可信应用。",
                level = RiskLevel.MEDIUM,
                weight = 50,
                category = Finding.Category.ENVIRONMENT
            )
        )
    }

    // ———————————————————— 未知来源安装 ————————————————————

    private fun checkUnknownSources(): List<Finding> {
        val enabled = try {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.INSTALL_NON_MARKET_APPS, 0
            ) == 1
        } catch (t: Throwable) {
            false
        }
        if (!enabled) return emptyList()

        return listOf(
            Finding(
                id = "env.unknown_sources",
                title = "允许安装未知来源应用",
                detail = "已放开非应用商店的安装渠道，可能被诱导安装来源不明的安装包。建议在需要时临时开启，用后关闭。",
                level = RiskLevel.MEDIUM,
                weight = 45,
                category = Finding.Category.ENVIRONMENT
            )
        )
    }

    // ———————————————————— 存储加密 ————————————————————

    private fun checkStorageEncryption(): List<Finding> {
        // Android 7.0 起默认强制文件级加密，此处仅做状态确认
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyList()
        val status = try {
            dpm.storageEncryptionStatus
        } catch (t: Throwable) {
            return emptyList()
        }
        if (status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
            status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY ||
            status == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
        ) {
            return emptyList()
        }
        return listOf(
            Finding(
                id = "env.encryption",
                title = "存储加密未处于激活状态",
                detail = "设备存储加密未启用，设备丢失时本地数据存在被直接读取的风险。",
                level = RiskLevel.MEDIUM,
                weight = 40,
                category = Finding.Category.ENVIRONMENT
            )
        )
    }

    // ———————————————————— 锁屏强度 ————————————————————

    private fun checkScreenLock(): List<Finding> {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val secure = try {
            km?.isDeviceSecure ?: false
        } catch (t: Throwable) {
            true
        }
        if (secure) return emptyList()

        return listOf(
            Finding(
                id = "env.screen_lock",
                title = "未设置锁屏密码",
                detail = "设备未设置锁屏保护，他人可直接解锁并使用。建议设置数字密码或生物识别。",
                level = RiskLevel.HIGH,
                weight = 60,
                category = Finding.Category.ENVIRONMENT
            )
        )
    }
}
