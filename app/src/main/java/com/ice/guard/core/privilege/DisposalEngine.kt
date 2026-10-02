package com.ice.guard.core.privilege

import android.content.Context

/**
 * 处置引擎：借助 Shizuku 提供的 shell(ADB) 权限，对已标记的高风险应用执行
 * **真实可生效**的操作。
 *
 * ## 能做什么（真能生效）
 * | 操作 | 命令 | 效果 |
 * |---|---|---|
 * | **终止进程** | `am force-stop <pkg>` | **立刻杀掉该应用的全部进程，并阻止系统自动重新拉起** |
 * | 停用 | `pm disable-user --user 0 <pkg>` | 应用被冻结，图标消失，无法运行 |
 * | 启用 | `pm enable <pkg>` | 恢复被停用的应用 |
 * | 卸载 | `pm uninstall --user 0 <pkg>` | 卸载（仅当前用户，可恢复） |
 * | 清数据 | `pm clear <pkg>` | 清除应用全部数据 |
 * | 撤销权限 | `pm revoke <pkg> <perm>` | 收回指定运行时权限 |
 * | 列表确认 | `pm list packages -d` | 核对停用状态 |
 *
 * ## 「1 秒终止」到底能到什么程度（诚实声明）
 * - **终止是真的**：`am force-stop` 由 shell 执行时会在亚秒级真正结束目标进程，
 *   且不像 `kill` 那样被系统立刻拉起。配合 `pm disable-user` 可做到"停不下来又起不来"。
 * - **但"发现"做不到实时**：本应用看不到别的应用的行为与内存，
 *   所以触发时机来自**可观测事件**（安装 / 开机 / 前台唤醒 / 周期巡检），
 *   而不是"监控到病毒发作"。瓶颈在检测，不在终止。
 * - **判定仍是启发式的**：本引擎只执行策略允许的处置动作，不下"这是病毒"的结论。
 */
class DisposalEngine(private val context: Context) {

    private val shell = ShellExecutor(context)

    /** 处置动作类型 */
    enum class Action(val title: String, val description: String, val confirmHint: String) {
        FORCE_STOP(
            "终止进程",
            "立刻杀掉该应用的全部进程，并阻止系统自动重新拉起；应用数据保留，下次手动打开仍可运行",
            "将立即终止该应用的所有进程。确定继续？"
        ),
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
            Action.FORCE_STOP -> shell.exec("am", "force-stop", packageName)
            Action.DISABLE -> shell.exec("pm", "disable-user", "--user", "0", packageName)
            Action.ENABLE -> shell.exec("pm", "enable", packageName)
            Action.UNINSTALL -> shell.exec("pm", "uninstall", "--user", "0", packageName)
            Action.CLEAR_DATA -> shell.exec("pm", "clear", packageName)
        }
    }

    /**
     * 快速终止一个应用：`am force-stop` 立即结束其全部进程；
     * [freeze] 为 true 时再追加 `pm disable-user`，使其无法被系统或自身拉活。
     *
     * 返回**按执行顺序**排列的结果列表（终止、冻结），调用方据此生成可核验的记录——
     * 而不是只报告一个笼统的"成功"。
     *
     * 说明：`am force-stop` 需要 shell 权限，未经 Shizuku 授权时必然失败，
     * 因此调用方应先确认 [isAvailable]。
     */
    fun terminate(packageName: String, freeze: Boolean = true): List<ShellResult> {
        require(packageName.isNotBlank()) { "packageName 不能为空" }
        ensureService()

        val out = mutableListOf<ShellResult>()
        out += shell.exec("am", "force-stop", packageName)
        if (freeze) {
            out += shell.exec("pm", "disable-user", "--user", "0", packageName)
        }
        return out
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
     *
     * 失败时返回 **null**，与"确实一个都没有"（空集合）区分开——
     * 否则巡检会把"查询失败"误报成"被冻结的应用又复活了"。
     */
    fun disabledPackagesOrNull(): Set<String>? {
        val r = shell.exec("pm", "list", "packages", "-d")
        if (!r.ok) return null
        return r.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** 查询停用列表；失败时视为空集合（仅用于展示场景） */
    fun disabledPackages(): Set<String> = disabledPackagesOrNull() ?: emptySet()

    /** 单个包是否处于停用状态 */
    fun isDisabled(packageName: String): Boolean = packageName in disabledPackages()
}
