package com.ice.guard.ui.audit

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.privilege.DisposalEngine
import com.ice.guard.core.privilege.ShizukuBridge
import com.ice.guard.core.scanner.PermissionAuditEngine
import com.ice.guard.di.AppContainer
import com.ice.guard.ui.privilege.PrivilegeGuideActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用权限审计详情页。
 * 展示全部已安装应用及其风险评分，支持按风险排序与查看权限明细；
 * 已授权 Shizuku 时，可对单个应用执行「处置」（停用 / 卸载 / 清除数据）。
 */
class AuditActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var loading: View
    private lateinit var countText: TextView
    private lateinit var privilegeHint: TextView
    private lateinit var adapter: AppAuditAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit)

        list = findViewById(R.id.auditList)
        loading = findViewById(R.id.auditLoading)
        countText = findViewById(R.id.auditCount)
        privilegeHint = findViewById(R.id.auditPrivilegeHint)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        adapter = AppAuditAdapter(onDispose = { showDisposeDialog(it) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        privilegeHint.setOnClickListener {
            startActivity(Intent(this, PrivilegeGuideActivity::class.java))
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        renderPrivilegeHint()
    }

    private fun renderPrivilegeHint() {
        val ready = ShizukuBridge.isReady(this)
        privilegeHint.text = if (ready) {
            "已获得处置权限 · 点击查看授权详情"
        } else {
            "未获得处置权限 · 点此前往授权（无线调试配对）"
        }
        privilegeHint.setTextColor(
            getColor(if (ready) R.color.risk_safe else R.color.risk_medium)
        )
    }

    private fun loadData() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                AppContainer.permissionAudit.auditAll(includeSystem = false)
            }
            loading.visibility = View.GONE
            adapter.submit(results)
            countText.text = "共 ${results.size} 个应用"
        }
    }

    /**
     * 处置弹窗。
     *
     * 这里刻意不提供「一键清理全部」——批量破坏性操作风险过高，
     * 必须让用户逐个确认，且破坏性动作（卸载 / 清数据）标红二次确认。
     */
    private fun showDisposeDialog(item: PermissionAuditEngine.AppAuditResult) {
        val disposal = AppContainer.disposal

        if (!disposal.isAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("尚未获得处置权限")
                .setMessage(disposal.unavailableReason() + "\n\n是否前往授权引导页？")
                .setPositiveButton("去授权") { _, _ ->
                    startActivity(Intent(this, PrivilegeGuideActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val actions = DisposalEngine.Action.entries
        val labels = actions.map { action ->
            if (action.destructive) "${action.title}（破坏性）" else action.title
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("处置：${item.appLabel}")
            .setItems(labels) { _, which ->
                confirmAndRun(item, actions[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmAndRun(
        item: PermissionAuditEngine.AppAuditResult,
        action: DisposalEngine.Action
    ) {
        AlertDialog.Builder(this)
            .setTitle(action.title)
            .setMessage(
                "${action.description}\n\n目标：${item.appLabel}\n包名：${item.packageName}\n\n${action.confirmHint}"
            )
            .setPositiveButton("执行") { _, _ -> runAction(item, action) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runAction(
        item: PermissionAuditEngine.AppAuditResult,
        action: DisposalEngine.Action
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppContainer.disposal.dispose(item.packageName, action)
            }
            val message = if (result.ok) {
                "${action.title}已下发：${item.packageName}"
            } else {
                "${action.title}失败：${result.stderr.ifBlank { result.stdout }.trim().take(120)}"
            }
            Toast.makeText(this@AuditActivity, message, Toast.LENGTH_LONG).show()
            // 处置后刷新列表，让"是否真的生效"体现在数据上，而不是只弹个提示
            loadData()
        }
    }
}
