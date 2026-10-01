package com.ice.guard.ui.audit

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.core.scanner.PermissionAuditEngine
import com.ice.guard.di.AppContainer
import kotlinx.coroutines.launch

/**
 * 应用权限审计详情页。
 * 展示全部已安装应用及其风险评分，支持按风险排序与查看权限明细。
 */
class AuditActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var loading: View
    private lateinit var countText: TextView
    private val adapter = AppAuditAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit)

        list = findViewById(R.id.auditList)
        loading = findViewById(R.id.auditLoading)
        countText = findViewById(R.id.auditCount)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        loadData()
    }

    private fun loadData() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = AppContainer.permissionAudit.auditAll(includeSystem = false)
            loading.visibility = View.GONE
            adapter.submit(results)
            countText.text = "共 ${results.size} 个应用"
        }
    }
}
