package com.ice.guard.data

import android.content.Context
import com.ice.guard.core.guard.GuardEvent
import org.json.JSONArray

/**
 * 本地拦截日志存储（SharedPreferences + 手写 JSON）。
 *
 * 只保留最近 [MAX_ENTRIES] 条，新的在最前。
 * 日志**不出设备**——本应用没有 INTERNET 权限，物理上无法外传。
 */
class InterceptLogStore(context: Context) {

    private val prefs = context.getSharedPreferences("ice_guard_intercept", Context.MODE_PRIVATE)

    /** 追加一条记录，返回其时间戳 */
    fun append(event: GuardEvent) {
        val list = loadRaw().toMutableList()
        list.add(0, event)
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        write(list)
    }

    fun loadRaw(): List<GuardEvent> {
        val json = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> GuardEvent.fromJson(arr.optJSONObject(i)) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun recent(n: Int): List<GuardEvent> = loadRaw().take(n)

    /** 只取真正执行过处置的记录，用于首页展示"已拦截 N 次" */
    fun actions(): List<GuardEvent> = loadRaw().filter { it.verdict.acted }

    fun count(): Int = loadRaw().size

    fun clear() = prefs.edit().remove(KEY_LOG).apply()

    private fun write(list: List<GuardEvent>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LOG, arr.toString()).apply()
    }

    companion object {
        private const val KEY_LOG = "events"
        private const val MAX_ENTRIES = 200
    }
}