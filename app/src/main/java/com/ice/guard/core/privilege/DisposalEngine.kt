package com.ice.guard.core.privilege

import android.content.Context

/**
 * 处置引擎：借助 Shizuku 提供的 shell(ADB) 权限，对已标记的高风险应用执行
 * **真实可生效**的操作。
 *
 * ## 能做什么（真能生效）
 * | 操作 | 命令 | 效果 |
 * |---|---|---|
 * | 停用 | `pm disable-user --user 0 <pkg>` | 应用被冻结，图标消失，无法运行 |
 * | 启用 | `pm enable <pkg>` | 恢复被停用的应用 |
 * | 卸载 | `pm uninstall --user 0 <pkg>` | 卸载（仅当前用户，可恢复） |
 * | 清数据 | `pm clear <pkg>` | 清除应用全部数据 |
 * | 撤销权限 | `pm revoke <pkg> <perm>` | 收回指定运行时权限 |
 * | 列表确认 | `pm list packages -d` | 核对停用状态 |
 *
 * ## 做不到什么（诚实声明）
 * - **看不到、也杀不掉别的应用进程**。即使有 shell 权限，`kill` 也只是让进程重启，
 *   且无法读取其内存与实时行为。所以本引擎没有"实时拦截病毒进程"这一项，
 *   不做无法验证的承诺。
 * - **判定仍是启发式的**。本引擎只执行用户确认过的处置动作，不下"这是病毒"的结论。
 */
class DisposalEngine(private val context: Context) {

    private val shell = ShellExecutor(context)

    /** 处置动作类型 */
    enum class Action(val title: String, val description: String, val confirmHint: String) {
        DISABLE(
            "停用应用",
            "冻结该应用：图标消失、无法运行，数据保留，可随时恢复",
            "停用后该应用将无法运行。确定继续？"
        ),
        ENABLE(
            "启用应用",
            "恢复被停用的应用",
            "确定恢复该应用？"
        ),
        UNINSTALL(
            "卸载应用",
            "卸载该应用（仅当前用户，系统内置应用可能失败）",
            "卸载会删除应用本身，数据可能一并丢失。确定继续？"
        ),
        CLEAR_DATA(
            "清除数据",
            "抹除该应用的全部本地数据（账号、缓存等），但保留应用本体",
            "清除数据不可撤销。确定继续？"
        );

        /** 该动作是否具备破坏性，用于 UI 标红与二次确认 */
        val destructive: Boolean
            get() = this == UNINSTALL || this == CLEAR_DATA
    }

    /** 执行前的可用性检查，UI 据此决定是否禁用按钮 */
    fun isAvailable(): Boolean = ShizukuBridge.isReady(context)

    fun unavailableReason(): String = when (ShizukuBridge.state(context)) {
        ShizukuBridge.State.NOT_INSTALLED -> "未检测到 Shizuku，无法执行处置操作"
        ShizukuBridge.State.SERVICE_NOT_RUNNING -> "Shizuku 服务未运行，请先通过无线调试启动服务"
        ShizukuBridge.State.PERMISSION_REQUIRED -> "尚未授权本应用，请先在授权引导页完成授权"
        ShizukuBridge.State.GRANTED -> ""
    }

    /**
     * 执行一次处置。包名以参数数组传入，不做字符串拼接。
     * 调用方需先确认 [isAvailable]，否则命令会以本应用权限执行而失败。
     *
     * 处置前会确保 UserService 已绑定——首次绑定可能耗时数百毫秒。
     */
    fun dispose(packageName: String, action: Action): ShellResult {
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        ensureService()
        return when (action) {
            Action.DISABLE -> shell.exec("pm", "disable-user", "--user", "0", packageName)
            Action.ENABLE -> shell.exec("pm", "enable", packageName)
            Action.UNINSTALL -> shell.exec("pm", "uninstall", "--user", "0", packageName)
            Action.CLEAR_DATA -> shell.exec("pm", "clear", packageName)
        }
    }

    /** 撤销单个运行时权限 */
    fun revokePermission(packageName: String, permission: String): ShellResult {
        ensureService()
        return shell.exec("pm", "revoke", packageName, permission)
    }

    /** 确保 shell 服务已绑定；已绑定则立即返回 */
    private fun ensureService() {
        if (ShizukuBridge.isReady(context) && shell.bindService()) return
    }

    /**
     * 查询当前处于「停用」状态的应用包名集合。
     * 用于在 UI 上把「已停用」与「正常」区分开——处置完不能只靠嘴说成功。
     */
    fun disabledPackages(): Set<String> {
        val r = shell.exec("pm", "list", "packages", "-d")
        if (!r.ok) return emptySet()
        return r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** 单个包是否处于停用状态 */
    fun isDisabled(packageName: String): Boolean = packageName in disabledPackages()
}
