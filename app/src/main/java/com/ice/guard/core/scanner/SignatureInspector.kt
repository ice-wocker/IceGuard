package com.ice.guard.core.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel
import java.security.MessageDigest

/**
 * 模块 5 · 安装来源与签名检测。
 *
 * 权限与组件都回答"应用能做什么"，这里回答"应用是谁、从哪来"：
 *  - **安装来源**：来自应用商店，还是浏览器 / 聊天软件投递的安装包（侧载）；
 *  - **调试标志**：是否 `debuggable` / `testOnly`——正式分发版本不应带这两个标志；
 *  - **签名摘要**：给出证书 SHA-256，供用户自行核对（本应用不做"黑名单比对"，
 *    因为那需要联网与样本库，而本项目不联网）。
 *
 * ## 能力边界
 * 本分析**不做**签名黑名单校验，也**不判断**证书是否"正规"。
 * 给出的 SHA-256 是给用户核对用的原始事实，不是结论。
 */
class SignatureInspector(private val context: Context) {

    /** 已知的应用商店包名。不在其中的来源统一按"侧载"处理。 */
    private val knownStores: Set<String> = setOf(
        "com.android.vending",              // Google Play
        "com.huawei.appmarket",             // 华为应用市场
        "com.xiaomi.market",                // 小米应用商店
        "com.heytap.market",                // OPPO
        "com.oppo.market",
        "com.bbk.appstore",                 // vivo
        "com.tencent.android.qqdownloader", // 应用宝
        "com.qihoo.appstore",               // 360
        "com.baidu.appsearch",              // 百度
        "com.sec.android.app.samsungapps",  // 三星
        "com.lenovo.leos.appstore",         // 联想
        "com.meizu.mstore"                  // 魅族
    )

    /** 检测结果 */
    data class InstallSource(
        val packageName: String,
        /** 安装器包名；null 表示系统未记录安装来源（通常即侧载） */
        val installerPackage: String?,
        val sideloaded: Boolean,
        val debuggable: Boolean,
        val testOnly: Boolean,
        val signatureSha256: String?
    ) {
        /** 面向用户的一行描述 */
        fun describe(): String = buildString {
            append(if (sideloaded) "非应用商店安装" else "来自 ${installerPackage}") 
            if (debuggable) append("，调试版本")
            if (testOnly) append("，测试专用包")
        }
    }

    fun inspect(packageName: String): InstallSource? {
        return try {
            val info = loadPackageInfo(packageName) ?: return null
            val appInfo: ApplicationInfo? = info.applicationInfo
            val installer = installerOf(packageName)
            val flags = appInfo?.flags ?: 0

            InstallSource(
                packageName = packageName,
                installerPackage = installer,
                sideloaded = installer == null || installer !in knownStores,
                debuggable = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                testOnly = (flags and ApplicationInfo.FLAG_TEST_ONLY) != 0,
                signatureSha256 = signatureDigest(info)
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** 按 API 版本选择签名读取方式（GET_SIGNING_CERTIFICATES 为 API 28+） */
    private fun loadPackageInfo(packageName: String): android.content.pm.PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

    /**
     * 取安装器包名。
     * API 30 起推荐 getInstallSourceInfo，低版本退回已废弃但可用的 getInstallerPackageName。
     */
    private fun installerOf(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    /** 签名证书的 SHA-256；取不到时返回 null，不抛异常 */
    private fun signatureDigest(info: android.content.pm.PackageInfo): String? = runCatching {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signers?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 生成发现项。仅对真正异常的特征产出，避免噪音。 */
    fun findings(source: InstallSource, appLabel: String): List<Finding> {
        val out = mutableListOf<Finding>()

        if (source.sideloaded) {
            out += Finding(
                id = "source.sideload.${source.packageName}",
                title = "非应用商店安装",
                detail = "$appLabel 不是通过应用商店安装的" +
                    "（系统未记录安装来源，或来源为 ${source.installerPackage ?: "未记录"}）。" +
                    "这类安装包可能来自浏览器下载、聊天软件投递或扫码，无法享受商店的审核与签名校验链路。",
                level = RiskLevel.MEDIUM,
                weight = 35,
                category = Finding.Category.SIGNATURE
            )
        }

        if (source.debuggable) {
            out += Finding(
                id = "source.debuggable.${source.packageName}",
                title = "调试版本（debuggable）",
                detail = "$appLabel 开启了 android:debuggable，可被调试器附加并注入代码。" +
                    "正式对外分发的应用不应带此标志，请确认它是你自己编译安装的。",
                level = RiskLevel.MEDIUM,
                weight = 45,
                category = Finding.Category.SIGNATURE
            )
        }

        if (source.testOnly) {
            out += Finding(
                id = "source.testonly.${source.packageName}",
                title = "测试专用包（testOnly）",
                detail = "$appLabel 被标记为测试专用包，通常由 Android Studio 直接安装，不具备正式分发特征。",
                level = RiskLevel.LOW,
                weight = 25,
                category = Finding.Category.SIGNATURE
            )
        }

        return out
    }
}