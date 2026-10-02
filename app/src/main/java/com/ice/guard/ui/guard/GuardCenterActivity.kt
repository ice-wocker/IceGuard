package com.ice.guard.ui.guard

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.guard.AutoResponder
import com.ice.guard.core.guard.ForegroundAppWatcher
import com.ice.guard.core.guard.GuardAccessibilityService
import com.ice.guard.core.guard.GuardChannel
import com.ice.guard.core.guard.GuardPolicy
import com.ice.guard.core.guard.GuardScheduler
import com.ice.guard.core.guard.GuardService
import com.ice.guard.core.guard.GuardSweep
import com.ice.guard.core.privilege.ShizukuBridge
import com.ice.guard.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 守护中心：所有守护相关开关与记录的集中页面。
 *
 * 设计原则：
 *  - 每个开关都写明「打开后会发生什么」，不写"智能防护"这类无法验证的话；
 *  - 自动处置默认关闭，且界面上直接标明它是否真的能生效（取决于 Shizuku 授权）；
 *  - 拦截日志如实展示命令返回，处置失败也照原样显示，不做美化。
 */
class GuardCenterActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var statsText: TextView
    private lateinit var guardModeSwitch: SwitchCompat
    private lateinit var autoRespondSwitch: SwitchCompat
    private lateinit var autoRespondHint: TextView
    private lateinit var freezeSwitch: SwitchCompat
    private lateinit var foregroundSwitch: SwitchCompat
    private lateinit var foregroundHint: TextView
    private lateinit var a11ySwitch: SwitchCompat
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdSeek: SeekBar
    private lateinit var logEmpty: TextView

    private val adapter = InterceptLogAdapter(onTerminate = { terminateNow(it.packageName) })

    /** 绑定 UI 期间禁止开关回调写回，避免"刷新界面"被误当成"用户修改" */
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard_center)

        stateText = findViewById(R.id.guardState)
        detailText = findViewById(R.id.guardDetail)
        statsText = findViewById(R.id.guardStats)
        guardModeSwitch = findViewById(R.id.guardModeSwitch)
        autoRespondSwitch = findViewById(R.id.autoRespondSwitch)
        autoRespondHint = findViewById(R.id.autoRespondHint)
        freezeSwitch = findViewById(R.id.freezeSwitch)
        foregroundSwitch = findViewById(R.id.foregroundSwitch)
        foregroundHint = findViewById(R.id.foregroundHint)
        a11ySwitch = findViewById(R.id.a11ySwitch)
        thresholdLabel = findViewById(R.id.thresholdLabel)
        thresholdSeek = findViewById(R.id.thresholdSeek)
        logEmpty = findViewById(R.id.logEmpty)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.clearLogButton).setOnClickListener { clearLog() }
        findViewById<View>(R.id.sweepButton).setOnClickListener { sweepNow() }

        findViewById<RecyclerView>(R.id.logList).apply {
            layoutManager = LinearLayoutManager(this@GuardCenterActivity)
            adapter = this@GuardCenterActivity.adapter
        }

        setupSwitches()
        setupThreshold()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ———————————————————— 开关 ————————————————————

    private fun setupSwitches() {
        guardModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AppContainer.guardConfig.update { it.copy(guardMode = checked) }
            // 守护模式联动常驻服务与周期巡检
            GuardService.sync(this)
            GuardScheduler.sync(this)
            render()
        }

        autoRespondSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked && !ShizukuBridge.isReady(this)) {
                // 没有 shell 权限时自动处置不可能生效，直接拦下并指路，
                // 而不是让用户以为"开了就安全了"
                binding = true
                autoRespondSwitch.isChecked = false
                binding = false
                Toast.makeText(
                    this,
                    "自动终止需要 Shizuku 授权才能生效，请先在「权限与处置」页完成授权",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }
            AppContainer.guardConfig.update { it.copy(autoRespond = checked) }
            GuardService.sync(this)
            render()
        }

        freezeSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            AppContainer.guardConfig.update { it.copy(freeze = checked) }
            render()
        }

        foregroundSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            if (checked && !ForegroundAppWatcher.hasPermission(this)) {
                binding = true
                foregroundSwitch.isChecked = false
                binding = false
                requestUsageAccess()
                return@setOnCheckedChangeListener
            }
            AppContainer.guardConfig.update { it.copy(watchForeground = checked) }
            // 前台监听跑在守护服务里，需要重启服务让它重新读取策略
            if (AppContainer.guardConfig.load().guardMode) GuardService.start(this)
            render()
        }

        // 无障碍服务只能由用户在系统设置里开启/关闭，应用无权代为操作
        a11ySwitch.setOnCheckedChangeListener { _, _ ->
            if (binding) return@setOnCheckedChangeListener
            openAccessibilitySettings()
        }
        a11ySwitch.setOnClickListener { openAccessibilitySettings() }
    }

    private fun setupThreshold() {
        thresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                thresholdLabel.text = "告警阈值：${GuardPolicy.THRESHOLD_RANGE.first + progress}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = GuardPolicy.THRESHOLD_RANGE.first + (seekBar?.progress ?: 0)
                AppContainer.guardConfig.update { it.copy(threshold = value) }
                render()
            }
        })
    }

    // ———————————————————— 渲染 ————————————————————

    private fun render() {
        val policy = AppContainer.guardConfig.load()
        val shizukuReady = ShizukuBridge.isReady(this)
        val a11yEnabled = isAccessibilityEnabled()

        binding = true

        guardModeSwitch.isChecked = policy.guardMode
        autoRespondSwitch.isChecked = policy.autoRespond
        freezeSwitch.isChecked = policy.freeze
        foregroundSwitch.isChecked = policy.watchForeground
        a11ySwitch.isChecked = a11yEnabled
        thresholdSeek.progress = (policy.threshold - GuardPolicy.THRESHOLD_RANGE.first)
            .coerceIn(0, thresholdSeek.max)
        thresholdLabel.text = "告警阈值：${policy.threshold}"

        binding = false

        stateText.text = if (policy.guardMode) "守护中" else "未开启守护"
        stateText.setTextColor(getColor(if (policy.guardMode) R.color.risk_safe else R.color.text_primary))

        detailText.text = buildString {
            append("检测通道：")
            append("安装/更新 ✓")
            append(" · 开机自检 ✓")
            append(if (policy.guardMode) " · 周期巡检 ✓" else " · 周期巡检 ✗")
            append(if (policy.watchForeground) " · 前台唤醒 ✓" else " · 前台唤醒 ✗")
            append(if (a11yEnabled) " · 无障碍 ✓" else " · 无障碍 ✗")
            append("\n处置能力：")
            append(if (shizukuReady) "已获得 Shizuku 授权，可终止进程" else "未授权，只能告警")
        }

        val actions = AppContainer.interceptLog.actions().size
        val total = AppContainer.interceptLog.count()
        statsText.text = "已处置 $actions 次 · 累计记录 $total 条"

        autoRespondHint.text = when {
            !shizukuReady -> "未获得 Shizuku 授权，开启也无法执行终止；当前只会告警"
            policy.autoRespond -> "已开启：评分 ≥ ${policy.threshold} 时自动 am force-stop" +
                (if (policy.freeze) " 并冻结" else "")
            else -> "关闭中：评分 ≥ ${policy.threshold} 时只提醒你，由你决定是否处置"
        }

        foregroundHint.text = when {
            !ForegroundAppWatcher.hasPermission(this) -> "未授予「使用情况访问」权限，点击开关将跳转系统设置授权"
            policy.watchForeground -> "已开启：约每秒检查一次前台应用（需守护模式常驻才持续生效）"
            else -> "已授权，但未开启；开启后约每秒检查一次前台应用"
        }

        renderLogs()
    }

    private fun renderLogs() {
        val logs = AppContainer.interceptLog.recent(50)
        adapter.submit(logs)
        logEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
    }

    // ———————————————————— 动作 ————————————————————

    private fun sweepNow() {
        Toast.makeText(this, "正在巡检…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                GuardSweep.run(this@GuardCenterActivity, GuardChannel.MANUAL)
            }
            Toast.makeText(
                this@GuardCenterActivity,
                "巡检完成：扫描 ${summary.scanned} 个应用，其中 ${summary.risky} 个达到阈值，" +
                    "处置 ${summary.acted} 次，状态漂移 ${summary.drifted} 项",
                Toast.LENGTH_LONG
            ).show()
            render()
        }
    }

    private fun terminateNow(packageName: String) {
        lifecycleScope.launch {
            val event = withContext(Dispatchers.IO) {
                AutoResponder.manualRespond(this@GuardCenterActivity, packageName)
            }
            Toast.makeText(
                this@GuardCenterActivity,
                event?.let { "${it.verdict.label}：${it.detail.take(120)}" } ?: "未能执行（应用可能已卸载）",
                Toast.LENGTH_LONG
            ).show()
            render()
        }
    }

    private fun clearLog() {
        AppContainer.interceptLog.clear()
        render()
    }

    // ———————————————————— 系统授权 ————————————————————

    /** 判断本应用的无障碍服务是否已被用户在系统设置中启用 */
    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val expected = ComponentName(this, GuardAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请在系统设置中找到「Ice 防护」并开启；关闭同样在此处操作", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun requestUsageAccess() {
        Toast.makeText(this, "请授予「使用情况访问」权限后返回本页重试", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .onFailure {
                // 少数 ROM 没有该页面，退回到应用详情页
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
    }
}