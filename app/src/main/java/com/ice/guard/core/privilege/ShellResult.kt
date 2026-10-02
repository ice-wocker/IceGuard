package com.ice.guard.core.privilege

/**
 * 一条 shell 命令的执行结果。
 *
 * 约定：永远不抛异常。调用方通过 [ok] 判断成败，
 * 失败原因写在 [stderr] 与 [exitCode] 里，便于如实展示给用户。
 */
data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val viaShizuku: Boolean
) {
    val ok: Boolean get() = exitCode == 0

    /** 面向用户的一行摘要 */
    fun summary(): String = when {
        ok -> "成功：$command"
        else -> "失败(exit=$exitCode)：${stderr.ifBlank { stdout }.trim().take(200)}"
    }
}
