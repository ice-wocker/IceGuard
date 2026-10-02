package com.ice.guard.core.rules

/**
 * 权限风险知识库（自研）。
 *
 * 设计说明：
 * 单一高危权限不足以判定一个应用有问题——很多正常应用也要存储权限。
 * 真正有判别力的是「权限组合」：例如「读取短信 + 联网」意味着可以把短信外传，
 * 而「短信 + 联网 + 无障碍」则意味着可以静默读取验证码并代你点击确认按钮，
 * 这是国内手机木马最典型的敛财路径。
 *
 * 本类包含两张表：
 *  1. 单权限权重表 PERMISSION_WEIGHTS
 *  2. 组合规则表 COMBO_RULES（核心判别力所在）
 *
 * 权重与阈值为本项目自行设定，可依据实际样本调整。
 */
object PermissionRules {

    /** 单权限基础权重。数值越高越敏感。 */
    val PERMISSION_WEIGHTS: Map<String, Int> = mapOf(
        // —— 一级：可直接造成资金/隐私损失 ——
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to 90,
        "android.permission.BIND_DEVICE_ADMIN" to 90,
        "android.permission.REQUEST_INSTALL_PACKAGES" to 80,
        "android.permission.READ_SMS" to 80,
        "android.permission.RECEIVE_SMS" to 80,
        "android.permission.PROCESS_OUTGOING_CALLS" to 70,
        "android.permission.CALL_PHONE" to 60,
        "android.permission.READ_CALL_LOG" to 70,
        "android.permission.SYSTEM_ALERT_WINDOW" to 65,
        "android.permission.WRITE_SETTINGS" to 60,

        // —— 二级：隐私数据读取 ——
        "android.permission.READ_CONTACTS" to 55,
        "android.permission.WRITE_CONTACTS" to 55,
        "android.permission.READ_PHONE_STATE" to 45,
        "android.permission.READ_EXTERNAL_STORAGE" to 35,
        "android.permission.WRITE_EXTERNAL_STORAGE" to 40,
        "android.permission.ACCESS_FINE_LOCATION" to 45,
        "android.permission.ACCESS_BACKGROUND_LOCATION" to 60,
        "android.permission.RECORD_AUDIO" to 55,
        "android.permission.CAMERA" to 50,
        "android.permission.READ_CALENDAR" to 30,
        "android.permission.BODY_SENSORS" to 40,
        "android.permission.QUERY_ALL_PACKAGES" to 30,
        "android.permission.PACKAGE_USAGE_STATS" to 35,

        // —— 三级：一般能力 ——
        "android.permission.INTERNET" to 10,
        "android.permission.ACCESS_NETWORK_STATE" to 5,
        "android.permission.WAKE_LOCK" to 5,
        "android.permission.FOREGROUND_SERVICE" to 10,
        "android.permission.POST_NOTIFICATIONS" to 5,
        "android.permission.VIBRATE" to 3
    )

    /**
     * 组合规则。命中即追加权重，并生成一条可读的判定原因。
     * 这是本引擎区别于「简单权限对照表」的关键。
     */
    data class ComboRule(
        val id: String,
        val requires: Set<String>,
        val bonus: Int,
        val level: RiskLevel,
        val reason: String
    )

    private const val SMS = "android.permission.READ_SMS"
    private const val SMS_RECV = "android.permission.RECEIVE_SMS"
    private const val NET = "android.permission.INTERNET"
    private const val A11Y = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES"
    private const val ADMIN = "android.permission.BIND_DEVICE_ADMIN"
    private const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val LOC = "android.permission.ACCESS_FINE_LOCATION"
    private const val BGLOC = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private const val CONTACTS = "android.permission.READ_CONTACTS"
    private const val MIC = "android.permission.RECORD_AUDIO"
    private const val CAM = "android.permission.CAMERA"
    private const val STORE = "android.permission.WRITE_EXTERNAL_STORAGE"
    private const val CALL_LOG = "android.permission.READ_CALL_LOG"
    private const val CALL = "android.permission.CALL_PHONE"

    val COMBO_RULES: List<ComboRule> = listOf(
        ComboRule(
            id = "combo.sms_net",
            requires = setOf(SMS, NET),
            bonus = 45,
            level = RiskLevel.CRITICAL,
            reason = "可读取短信且能联网上传，具备静默窃取验证码短信的技术条件"
        ),
        ComboRule(
            id = "combo.sms_net_a11y",
            requires = setOf(SMS_RECV, NET, A11Y),
            bonus = 60,
            level = RiskLevel.CRITICAL,
            reason = "收短信 + 联网 + 无障碍服务：可自动读取验证码并模拟点击，是典型的盗刷木马组合"
        ),
        ComboRule(
            id = "combo.a11y_net",
            requires = setOf(A11Y, NET),
            bonus = 40,
            level = RiskLevel.CRITICAL,
            reason = "无障碍服务 + 联网：可读取屏幕内容并远程上报或接受指令，风险极高"
        ),
        ComboRule(
            id = "combo.install_overlay",
            requires = setOf(INSTALL, OVERLAY),
            bonus = 45,
            level = RiskLevel.HIGH,
            reason = "可安装应用 + 悬浮窗：可弹出覆盖层诱导点击并静默安装其他程序（全家桶常见手法）"
        ),
        ComboRule(
            id = "combo.admin_overlay",
            requires = setOf(ADMIN, OVERLAY),
            bonus = 45,
            level = RiskLevel.HIGH,
            reason = "设备管理器 + 悬浮窗：难以卸载且可遮挡界面，具备锁机勒索特征"
        ),
        ComboRule(
            id = "combo.bgloc_net",
            requires = setOf(BGLOC, NET),
            bonus = 35,
            level = RiskLevel.HIGH,
            reason = "后台定位 + 联网：可在无感知状态下持续上报位置轨迹"
        ),
        ComboRule(
            id = "combo.contacts_net",
            requires = setOf(CONTACTS, NET),
            bonus = 30,
            level = RiskLevel.MEDIUM,
            reason = "通讯录 + 联网：可导出并上传完整联系人列表"
        ),
        ComboRule(
            id = "combo.mic_cam_net",
            requires = setOf(MIC, CAM, NET),
            bonus = 40,
            level = RiskLevel.HIGH,
            reason = "麦克风 + 摄像头 + 联网：可采集音视频并外传，存在窃听窃视风险"
        ),
        ComboRule(
            id = "combo.store_install",
            requires = setOf(STORE, INSTALL),
            bonus = 25,
            level = RiskLevel.MEDIUM,
            reason = "存储写入 + 安装应用：可从本地投放安装包并直接安装"
        ),

        // —— 以下为 1.1.0 扩充：覆盖「无联网但仍可作案」与「下载-投放-安装」链条 ——

        ComboRule(
            id = "combo.sms_a11y",
            requires = setOf(SMS, A11Y),
            bonus = 50,
            level = RiskLevel.CRITICAL,
            reason = "读取短信 + 无障碍服务：无需联网也能静默读取验证码并代你点击确认，本地即可完成盗刷"
        ),
        ComboRule(
            id = "combo.sms_recv_a11y",
            requires = setOf(SMS_RECV, A11Y),
            bonus = 50,
            level = RiskLevel.CRITICAL,
            reason = "接收短信 + 无障碍服务：可实时截获验证码短信并自动完成后续操作"
        ),
        ComboRule(
            id = "combo.call_net",
            requires = setOf(CALL_LOG, NET),
            bonus = 35,
            level = RiskLevel.HIGH,
            reason = "通话记录 + 联网：可导出完整通话往来用于画像或诈骗筛选"
        ),
        ComboRule(
            id = "combo.call_out",
            requires = setOf(CALL, CALL_LOG, NET),
            bonus = 45,
            level = RiskLevel.HIGH,
            reason = "拨号 + 通话记录 + 联网：具备对外拨号并回传结果的能力，是电话诈骗类程序的典型特征"
        ),
        ComboRule(
            id = "combo.overlay_net",
            requires = setOf(OVERLAY, NET),
            bonus = 35,
            level = RiskLevel.HIGH,
            reason = "悬浮窗 + 联网：可接收远端指令动态绘制覆盖层，遮挡界面诱导操作"
        ),
        ComboRule(
            id = "combo.install_net",
            requires = setOf(INSTALL, NET),
            bonus = 40,
            level = RiskLevel.HIGH,
            reason = "联网 + 安装应用：可自行下载安装包并静默投放，是「拉活 / 全家桶」的完整闭环"
        ),
        ComboRule(
            id = "combo.admin_install",
            requires = setOf(ADMIN, INSTALL),
            bonus = 50,
            level = RiskLevel.CRITICAL,
            reason = "设备管理器 + 安装应用：既难以卸载又能自行投放新程序，清除成本极高"
        ),
        ComboRule(
            id = "combo.profile_net",
            requires = setOf(CONTACTS, SMS, CALL_LOG, NET),
            bonus = 55,
            level = RiskLevel.CRITICAL,
            reason = "通讯录 + 短信 + 通话记录 + 联网：可一次性导出完整社交与通信画像，符合数据贩售类程序特征"
        ),
        ComboRule(
            id = "combo.spy_local",
            requires = setOf(MIC, CAM, BGLOC),
            bonus = 45,
            level = RiskLevel.HIGH,
            reason = "录音 + 摄像头 + 后台定位：不依赖网络也可持续采集成音视频与轨迹，本地留存同样构成窃听窃视"
        )
    )

    /**
     * 计算一个应用的权限风险分（0-100）。
     * 算法：取单权限最高分 + 所有命中组合的加权累加，再压缩到 100 以内。
     * 采用「最高单项 + 组合加成」而非简单求和，避免权限多的正常应用被误判为高危。
     */
    fun scoreOf(grantedPermissions: Collection<String>): Int {
        if (grantedPermissions.isEmpty()) return 0
        val set = grantedPermissions.toSet()

        val singleMax = set.maxOfOrNull { PERMISSION_WEIGHTS[it] ?: 0 } ?: 0
        val comboBonus = COMBO_RULES
            .filter { set.containsAll(it.requires) }
            .sumOf { it.bonus }

        // 组合加成按 60% 折算，避免多项组合叠加直接顶满
        val raw = singleMax + (comboBonus * 0.6).toInt()
        return raw.coerceIn(0, 100)
    }

    /** 命中的所有组合规则，用于生成报告中的判定依据 */
    fun matchedCombos(grantedPermissions: Collection<String>): List<ComboRule> {
        val set = grantedPermissions.toSet()
        return COMBO_RULES.filter { set.containsAll(it.requires) }
    }

    /** 反查某权限的可读名称与权重 */
    fun weightOf(permission: String): Int = PERMISSION_WEIGHTS[permission] ?: 0

    /** 该权限是否属于本知识库关注范围 */
    fun isTracked(permission: String): Boolean = PERMISSION_WEIGHTS.containsKey(permission)
}
