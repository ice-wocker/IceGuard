package com.ice.guard.core.scanner

import android.content.Context
import android.os.Environment
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.PermissionRules
import com.ice.guard.core.rules.RiskLevel
import java.io.File
import java.security.MessageDigest

/**
 * 模块 2 · 安装包（APK）静态扫描引擎。
 *
 * 工作流程：
 *  1. 在指定目录中递归查找 .apk 文件（限制深度，避免全盘遍历过慢）
 *  2. 用自研 AxmlParser 读出其声明的权限（不解压全部内容）
 *  3. 复用 PermissionRules 组合规则评估风险
 *  4. 计算 SHA-256 便于用户自行核对
 *
 * 能力边界（诚实声明）：
 *  - 本引擎不包含病毒特征库，无法判定「这个 APK 是某个已知病毒」。
 *  - 评估依据是权限与组件的静态特征，属于启发式提示。
 *  - 不联网、不上传任何文件，全部分析在设备本地完成。
 */
class ApkScanEngine(private val context: Context) {

    /** 单个 APK 的扫描结果 */
    data class ApkScanResult(
        val file: File,
        val manifest: AxmlParser.ManifestInfo,
        val sha256: String?,
        val score: Int,
        val level: RiskLevel,
        val findings: List<Finding>
    )

    /**
     * 扫描常见目录中的 APK 文件。
     * 默认扫描 Download 与根目录下的常见路径，深度 3 层，
     * 单次最多处理 200 个文件，防止耗时失控。
     */
    fun scanCommonDirs(): List<Finding> {
        val roots = buildList {
            Environment.getExternalStorageDirectory()?.let { add(it) }
            context.getExternalFilesDir(null)?.let { add(it) }
        }.distinct()

        val apks = mutableListOf<File>()
        roots.forEach { root ->
            collectApks(root, apks, depth = 0, maxDepth = 3, limit = MAX_FILES)
        }

        return apks
            .distinctBy { it.absolutePath }
            .mapNotNull { safeScan(it) }
            .flatMap { it.findings }
            .sortedByDescending { it.weight }
            .take(MAX_FINDINGS)
    }

    /** 扫描单个 APK，返回完整结果（供 UI 详情页使用） */
    fun scanOne(apk: File): ApkScanResult? = safeScan(apk)

    private fun safeScan(apk: File): ApkScanResult? = try {
        scan(apk)
    } catch (t: Throwable) {
        null
    }

    private fun scan(apk: File): ApkScanResult {
        val manifest = AxmlParser.parseFromApk(apk)
        val perms = manifest.permissions
        val score = PermissionRules.scoreOf(perms)
        val level = RiskLevel.fromScore(score)
        val sha = runCatching { sha256Of(apk) }.getOrNull()

        val findings = mutableListOf<Finding>()

        // 只对中风险及以上的 APK 生成发现项
        if (level >= RiskLevel.MEDIUM) {
            val combos = PermissionRules.matchedCombos(perms)
            val detail = buildString {
                append("位于 ${apk.parent ?: "未知路径"}，")
                append("声明权限 ${perms.size} 项，风险分 $score")
                if (manifest.packageName != null) append("，包名 ${manifest.packageName}")
                if (sha != null) append("，SHA-256 ${sha.take(16)}…")
                if (combos.isNotEmpty()) {
                    append("。命中组合：")
                    append(combos.joinToString("；") { it.reason })
                }
                if (!manifest.success) {
                    append("。（注意：清单解析异常——${manifest.error}）")
                }
            }

            findings += Finding(
                id = "apk.${apk.absolutePath}",
                title = apk.name,
                detail = detail,
                level = level,
                weight = score,
                category = Finding.Category.APK
            )
        }

        return ApkScanResult(apk, manifest, sha, score, level, findings)
    }

    private fun collectApks(dir: File, out: MutableList<File>, depth: Int, maxDepth: Int, limit: Int) {
        if (depth > maxDepth || out.size >= limit) return
        val children = try {
            dir.listFiles() ?: return
        } catch (t: Throwable) {
            return
        }
        children.forEach { f ->
            if (out.size >= limit) return
            if (f.isDirectory) {
                // 跳过明显的系统/缓存目录，降低开销
                if (f.name !in SKIP_DIRS) {
                    collectApks(f, out, depth + 1, maxDepth, limit)
                }
            } else if (f.name.endsWith(".apk", ignoreCase = true)) {
                out += f
            }
        }
    }

    /** 计算 SHA-256，流式读取避免一次性载入大文件 */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_FILES = 200
        private const val MAX_FINDINGS = 50
        private val SKIP_DIRS = setOf(
            "Android", "data", "obb", "cache", ".thumbnails", "Lost.Dir"
        )
    }
}
