package com.ice.guard.ui.privilege

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ice.guard.R
import com.ice.guard.core.privilege.ShizukuBridge
import com.ice.guard.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 权限授权与处置引导页。
 *
 * 页面把「无线调试配对」的实际步骤摊开写清楚，并根据 [ShizukuBridge.State]
 * 给出当前该做什么。授权成功后可查询已停用应用，验证处置真的生效。
 */
class PrivilegeGuideActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var detailText: TextView
    private lateinit var authorizeButton: TextView
    private lateinit var disabledList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privilege_guide)

        stateText = findViewById(R.id.privilegeState)
        detailText = findViewById(R.id.privilegeDetail)
        authorizeButton = findViewById(R.id.authorizeButton)
        disabledList = findViewById(R.id.disabledList)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { refresh() }
        authorizeButton.setOnClickListener { requestPermission() }

        findViewById<View>(R.id.openShizukuButton).setOnClickListener {
            launchApp(ShizukuBridge.SHIZUKU_PACKAGE)
        }
        findViewById<View>(R.id.openDevOptionsButton).setOnClickListener {
            openWirelessDebugging()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val state = ShizukuBridge.state(this)
        stateText.text = state.label

        val colorRes = when (state) {
            ShizukuBridge.State.GRANTED -> R.color.risk_safe
            ShizukuBridge.State.PERMISSION_REQUIRED -> R.color.risk_medium
            else -> R.color.risk_high
        }
        stateText.setTextColor(getColor(colorRes))

        detailText.text = when (state) {
            ShizukuBridge.State.NOT_INSTALLED ->
                "未检测到 Shizuku。请先按下方步骤安装并启动 Shizuku 服务。"
            ShizukuBridge.State.SERVICE_NOT_RUNNING ->
                "Shizuku 已安装，但服务未运行。请在 Shizuku 内选择「通过无线调试启动」，按下方步骤完成配对。"
            ShizukuBridge.State.PERMISSION_REQUIRED ->
                "服务已运行，还差一步：点下方「请求授权」，在弹窗中允许 IceGuard。可勾选「始终允许」避免重复询问。"
            ShizukuBridge.State.GRANTED ->
                "已授权，可执行处置操作。服务端版本 ${ShizukuBridge.serviceVersion()}。"
        }

        authorizeButton.isEnabled = state == ShizukuBridge.State.PERMISSION_REQUIRED
        authorizeButton.alpha = if (authorizeButton.isEnabled) 1f else 0.45f

        if (state == ShizukuBridge.State.GRANTED) {
            loadDisabledPackages()
        } else {
            disabledList.text = "授权后可查询"
        }
    }

    private fun requestPermission() {
        if (!ShizukuBridge.requestPermission()) {
            refresh()
            return
        }
        // 弹窗是异步的，延迟一拍再读结果；用户操作后回到本页 onResume 还会再刷新一次
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1200)
            val result = ShizukuBridge.consumeRequestResult()
            if (result == false) {
                detailText.text = "授权被拒绝。可在 Shizuku 应用中重新授权，或再次点击「请求授权」。"
            }
            refresh()
        }
    }

    private fun loadDisabledPackages() {
        disabledList.text = "查询中…"
        lifecycleScope.launch {
            val set = withContext(Dispatchers.IO) { AppContainer.disposal.disabledPackages() }
            disabledList.text = if (set.isEmpty()) {
                "当前没有被停用的应用。"
            } else {
                "共 ${set.size} 个：\n" + set.sorted().joinToString("\n") { "· $it" }
            }
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            runCatching { startActivity(intent) }
        } else {
            // 未安装则尝试打开应用市场详情页
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                )
            }
        }
    }

    /** 打开无线调试设置页；不同厂商 ROM 路径不一致，逐级回退到开发者选项、应用详情 */
    private fun openWirelessDebugging() {
        val candidates = listOf(
            "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
        for (action in candidates) {
            val intent = when (action) {
                "android.settings.APPLICATION_DEVELOPMENT_SETTINGS" ->
                    Intent(action)
                else ->
                    Intent(action, Uri.parse("package:$packageName"))
            }
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }
}
