package com.ice.guard.core.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * shell 命令执行器（经 Shizuku UserService）。
 *
 * ## 通道
 * 1. **Shizuku 通道**：把命令交给运行在 shell(uid 2000)/root 身份下的
 *    [UserService] 执行，因此 `pm disable-user`、`pm uninstall` 这类命令能生效。
 * 2. **本应用通道**：直接 `Runtime.exec`，能力等同本应用自身权限，
 *    仅用于不需要特殊权限的只读查询。
 *
 * 需要处置能力时必须走通道 1，调用方先确认 [ShizukuBridge.isReady]。
 */
class ShellExecutor(private val context: Context) {

    @Volatile
    private var service: com.ice.guard.IUserService? = null

    private val serviceArgs: Shizuku.UserServiceArgs by lazy {
        val cn = ComponentName(context.packageName, UserService::class.java.name)
        Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            // tag 必须固定：类名会被 R8 混淆，Shizuku 靠 tag 判断"是否同一个服务"
            .tag("IceGuardShellService@1")
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = com.ice.guard.IUserService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /**
     * 绑定 UserService。绑定是异步的，这里做一次有上限的等待。
     * @return 是否在超时前拿到服务
     */
    fun bindService(timeoutMillis: Long = BIND_TIMEOUT_MS): Boolean {
        if (service != null) return true
        if (!ShizukuBridge.isReady(context)) return false

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = com.ice.guard.IUserService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        return runCatching {
            Shizuku.bindUserService(serviceArgs, conn)
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && service != null
        }.getOrDefault(false)
    }

    fun unbindService() {
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        service = null
    }

    /** 执行一条命令。命令以数组传入，不做字符串拼接。 */
    fun exec(vararg command: String): ShellResult {
        val cmd = command.toList()
        if (ShizukuBridge.isReady(context)) {
            val svc = service
            if (svc != null) {
                val remote = runCatching { svc.exec(cmd.toTypedArray()) }.getOrNull()
                if (remote != null) return fromRemote(cmd, remote)
            }
        }
        return execLocal(cmd)
    }

    private fun fromRemote(cmd: List<String>, out: Array<String>): ShellResult {
        val code = out.getOrNull(0)?.toIntOrNull() ?: -1
        return ShellResult(
            command = cmd.joinToString(" "),
            exitCode = code,
            stdout = out.getOrNull(1).orEmpty(),
            stderr = out.getOrNull(2).orEmpty(),
            viaShizuku = true
        )
    }

    private fun execLocal(cmd: List<String>): ShellResult = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val stdout = AtomicReference("")
        val stderr = AtomicReference("")
        val t1 = Thread { stdout.set(process.inputStream.bufferedReader().use { it.readText() }) }
        val t2 = Thread { stderr.set(process.errorStream.bufferedReader().use { it.readText() }) }
        t1.start(); t2.start()

        if (!process.waitFor(LOCAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { process.destroy() }
            return@runCatching ShellResult(
                cmd.joinToString(" "), -2, stdout.get(), "命令超时（${LOCAL_TIMEOUT_SECONDS}s）", false
            )
        }
        t1.join(1000); t2.join(1000)
        ShellResult(
            command = cmd.joinToString(" "),
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            stdout = stdout.get(),
            stderr = stderr.get(),
            viaShizuku = false
        )
    }.getOrElse { t ->
        ShellResult(
            command = cmd.joinToString(" "),
            exitCode = -1,
            stdout = "",
            stderr = "本地执行失败：${t.message ?: t.javaClass.simpleName}",
            viaShizuku = false
        )
    }

    companion object {
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val LOCAL_TIMEOUT_SECONDS = 15L
    }
}
