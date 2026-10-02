package com.ice.guard.core.privilege

import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Shizuku 桥接层——只做"状态查询 + 授权请求"，不含任何处置逻辑。
 *
 * ## 关于「内置 + 无线配对」这件事（重要，如实声明）
 *
 * 无线调试配对**不是**可以由本应用自动完成的事：
 * Shizuku 要以 shell 身份运行服务进程，而"以 shell 身份启动"这一步，
 * 必须由用户在系统「开发者选项 → 无线调试」里手动完成配对，
 * 或者在电脑上执行一次 `adb shell sh .../start.sh`。
 *
 * 本应用能做的是：**引导用户走完这套流程**，并在用户授权后借 Shizuku 的
 * shell 能力执行 `pm` 命令。把 Shizuku 的代码打进本 APK 并不会让它
 * 自动获得 shell 权限——那不成立，所以本应用不这么做。
 */
object ShizukuBridge {

    /** 授权状态。用枚举而不是多个 boolean，避免出现"看似可用实则未授权"的中间态。 */
    enum class State(val label: String) {
        /** Shizuku 应用未安装 */
        NOT_INSTALLED("未安装 Shizuku"),
        /** 已安装但服务未运行（服务需要用户以无线调试/ADB 启动一次） */
        SERVICE_NOT_RUNNING("Shizuku 服务未运行"),
        /** 服务在跑，但本应用尚未获得授权 */
        PERMISSION_REQUIRED("尚未授权给本应用"),
        /** 已授权，可用 */
        GRANTED("已授权，可用")
    }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0x5A1

    /** 授权结果回调。null 表示尚未回调。 */
    @Volatile
    private var lastRequestResult: Boolean? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                lastRequestResult = grantResult == PackageManager.PERMISSION_GRANTED
            }
        }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // 服务进程被杀（例如手机重启、Shizuku 手动停止），下一次查询会回到 SERVICE_NOT_RUNNING
    }

    /**
     * 注册 Shizuku 监听。必须在 Application.onCreate / Activity.onCreate 中调用一次；
     * 重复调用是安全的（Shizuku 内部去重）。
     */
    fun register() {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }
    }

    fun unregister() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
    }

    /** Shizuku 应用是否已安装 */
    fun isInstalled(context: android.content.Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 查询当前状态。不会抛异常。 */
    fun state(context: android.content.Context): State = runCatching {
        if (!isInstalled(context)) return State.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return State.SERVICE_NOT_RUNNING
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (granted) State.GRANTED else State.PERMISSION_REQUIRED
    }.getOrDefault(State.SERVICE_NOT_RUNNING)

    /** 是否具备可用的 shell 权限 */
    fun isReady(context: android.content.Context): Boolean = state(context) == State.GRANTED

    /**
     * 触发授权弹窗。仅在状态为 [State.PERMISSION_REQUIRED] 时有意义。
     * 若用户曾勾选"始终允许"，Shizuku 会直接放行。
     */
    fun requestPermission(): Boolean {
        lastRequestResult = null
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) {
                // 旧版（ADB 直连）不需要 runtime 授权弹窗
                return true
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                lastRequestResult = true
                return true
            }
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    /** 读取上一次授权请求的结果，null 表示用户还没做选择 */
    fun consumeRequestResult(): Boolean? {
        val r = lastRequestResult
        lastRequestResult = null
        return r
    }

    /** 判断当前是否处于「旧版 ADB 直连」模式（无需 runtime 授权） */
    fun isPreV11(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.isPreV11() }
        .getOrDefault(false)

    /** Shizuku 服务端版本，用于展示。服务未运行时返回 -1 */
    fun serviceVersion(): Int = runCatching { if (Shizuku.pingBinder()) Shizuku.getVersion() else -1 }
        .getOrDefault(-1)
}
