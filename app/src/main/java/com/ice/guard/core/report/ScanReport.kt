package com.ice.guard.core.report

import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次完整体检的报告。
 * 手写 JSON 序列化（用平台自带的 org.json），不引入 Gson/Moshi。
 */
data class ScanReport(
    val timestamp: Long,
    val overallScore: Int,
    val modules: List<ModuleResult>
) {
    data class ModuleResult(
        val moduleId: String,
        val moduleName: String,
        val score: Int,
        val findings: List<Finding>
    )

    val level: RiskLevel get() = RiskLevel.fromScore(overallScore)

    /** 全局汇总的发现项，按权重降序 */
    val allFindings: List<Finding>
        get() = modules.flatMap { it.findings }.sortedByDescending { it.weight }

    fun toJson(): String {
        val root = JSONObject()
        root.put("timestamp", timestamp)
        root.put("overallScore", overallScore)
        root.put("level", level.name)

        val arr = JSONArray()
        modules.forEach { m ->
            val mo = JSONObject()
            mo.put("moduleId", m.moduleId)
            mo.put("moduleName", m.moduleName)
            mo.put("score", m.score)
            val fa = JSONArray()
            m.findings.forEach { f ->
                fa.put(JSONObject().apply {
                    put("id", f.id)
                    put("title", f.title)
                    put("detail", f.detail)
                    put("level", f.level.name)
                    put("weight", f.weight)
                    put("category", f.category.name)
                })
            }
            mo.put("findings", fa)
            arr.put(mo)
        }
        root.put("modules", arr)
        return root.toString(2)
    }

    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# Ice 防护 · 安全体检报告")
        sb.appendLine()
        sb.appendLine("- 体检时间：${formatTime(timestamp)}")
        sb.appendLine("- 综合评分：**$overallScore / 100**（${level.label}）")
        sb.appendLine("- 发现问题：${allFindings.size} 项")
        sb.appendLine()
        modules.forEach { m ->
            sb.appendLine("## ${m.moduleName}（得分 ${m.score}/100）")
            sb.appendLine()
            if (m.findings.isEmpty()) {
                sb.appendLine("未发现问题。")
                sb.appendLine()
            } else {
                m.findings.forEach { f ->
                    sb.appendLine("- **[${f.level.label}]** ${f.title}")
                    sb.appendLine("  - ${f.detail}")
                }
                sb.appendLine()
            }
        }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("> 本报告由 Ice 防护本地生成。评分基于权限组合与设备环境启发式评估，")
        sb.appendLine("> 用于提示关注方向，不构成「某应用为恶意软件」的结论。")
        return sb.toString()
    }

    companion object {
        fun formatTime(ts: Long): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            return sdf.format(java.util.Date(ts))
        }
    }
}
