package com.ice.guard.data

import android.content.Context
import com.ice.guard.core.report.ScanReport
import org.json.JSONArray
import org.json.JSONObject

/**
 * 体检历史存储（自研轻量实现）。
 *
 * 设计取舍：历史记录结构简单、条目有限（上限 50 条），
 * 使用 SharedPreferences + 手写 JSON 即可，无需引入数据库。
 */
class ScanHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("ice_guard_history", Context.MODE_PRIVATE)

    /** 保存一次体检报告（追加到队首，超出上限自动裁剪） */
    fun save(report: ScanReport) {
        val list = loadRaw().toMutableList()
        list.add(0, report)
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        writeRaw(list)
    }

    /** 读取全部历史，最新的在前 */
    fun loadRaw(): List<ScanReport> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> parseReport(arr.optJSONObject(i)) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 仅取最近 n 条 */
    fun recent(n: Int): List<ScanReport> = loadRaw().take(n)

    fun clear() = prefs.edit().remove(KEY_HISTORY).apply()

    /** 与上一次体检对比，返回分数差值（正数表示分数上升即更安全） */
    fun scoreDelta(current: Int): Int? {
        val last = loadRaw().firstOrNull() ?: return null
        return current - last.overallScore
    }

    private fun writeRaw(list: List<ScanReport>) {
        val arr = JSONArray()
        list.forEach { arr.put(reportToJson(it)) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun reportToJson(r: ScanReport): JSONObject = JSONObject().apply {
        put("timestamp", r.timestamp)
        put("overallScore", r.overallScore)
        val ma = JSONArray()
        r.modules.forEach { m ->
            ma.put(JSONObject().apply {
                put("moduleId", m.moduleId)
                put("moduleName", m.moduleName)
                put("score", m.score)
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
                put("findings", fa)
            })
        }
        put("modules", ma)
    }

    private fun parseReport(o: JSONObject?): ScanReport? {
        if (o == null) return null
        return try {
            val modules = mutableListOf<ScanReport.ModuleResult>()
            val ma = o.optJSONArray("modules") ?: JSONArray()
            for (i in 0 until ma.length()) {
                val m = ma.optJSONObject(i) ?: continue
                val findings = mutableListOf<com.ice.guard.core.rules.Finding>()
                val fa = m.optJSONArray("findings") ?: JSONArray()
                for (j in 0 until fa.length()) {
                    val f = fa.optJSONObject(j) ?: continue
                    findings += com.ice.guard.core.rules.Finding(
                        id = f.optString("id"),
                        title = f.optString("title"),
                        detail = f.optString("detail"),
                        level = runCatching {
                            com.ice.guard.core.rules.RiskLevel.valueOf(f.optString("level"))
                        }.getOrDefault(com.ice.guard.core.rules.RiskLevel.SAFE),
                        weight = f.optInt("weight"),
                        category = runCatching {
                            com.ice.guard.core.rules.Finding.Category.valueOf(f.optString("category"))
                        }.getOrDefault(com.ice.guard.core.rules.Finding.Category.STORAGE)
                    )
                }
                modules += ScanReport.ModuleResult(
                    moduleId = m.optString("moduleId"),
                    moduleName = m.optString("moduleName"),
                    score = m.optInt("score"),
                    findings = findings
                )
            }
            ScanReport(
                timestamp = o.optLong("timestamp"),
                overallScore = o.optInt("overallScore"),
                modules = modules
            )
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val MAX_ENTRIES = 50
    }
}
