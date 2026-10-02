package com.ice.guard.core.privilege

import android.content.Context
import android.system.Os
import androidx.annotation.Keep
import com.ice.guard.IUserService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 在 Shizuku 服务进程中运行的用户服务。
 *
 * ## 为什么需要它
 * Shizuku 的 `newProcess` 是 API 库内部私有方法，客户端无法直接调用。
 * 官方推荐的做法是把要执行的动作放进一个 UserService——该服务被 Shizuku
 * 以 **shell(uid 2000) 或 root 身份**启动，因此服务里执行的命令天然带 ADB 权限。
 *
 * ## 边界
 * 拿到的是 shell(uid 2000) 权限，不是 uid 0。因此能做 `pm disable-user`、
 * `pm uninstall`、`pm clear` 这类包管理操作，但**读不到别的应用进程内存**，
 * 也无法"实时杀掉病毒进程"。
 */
class UserService : IUserService.Stub {

    @Keep
    constructor()

    @Keep
    constructor(context: Context) {
        // v13 起 Shizuku 会传入 Context 构造；此处无需它，保留以匹配官方签名
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun getServiceUid(): Int = Os.getuid()

    /**
     * 执行命令并返回 [exitCode, stdout, stderr]。
     * 不抛异常：任何失败都以非零 exitCode + stderr 文案返回。
     */
    override fun exec(command: Array<out String>?): Array<String> {
        if (command.isNullOrEmpty()) {
            return arrayOf("-1", "", "空命令")
        }
        return try {
            val process = ProcessBuilder(command.toList())
                .redirectErrorStream(false)
                .start()

            val stdout = readAll(process.inputStream)
            val stderr = readAll(process.errorStream)

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runCatching { process.destroy() }
                return arrayOf("-2", stdout, "命令超时（${TIMEOUT_SECONDS}s）")
            }
            arrayOf(process.exitValue().toString(), stdout, stderr)
        } catch (t: Throwable) {
            arrayOf("-1", "", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun readAll(stream: java.io.InputStream): String =
        runCatching { BufferedReader(InputStreamReader(stream)).use { it.readText() } }
            .getOrDefault("")

    companion object {
        private const val TIMEOUT_SECONDS = 15L
    }
}
