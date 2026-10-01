package com.ice.guard.ui.apk

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.di.AppContainer
import kotlinx.coroutines.launch

/**
 * 安装包静态扫描页。
 * 扫描存储中的 APK 文件，解析其声明的权限并评估风险。
 */
class ApkScanActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: View
    private val adapter = ApkFindingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk)

        list = findViewById(R.id.apkList)
        status = findViewById(R.id.apkStatus)
        progress = findViewById(R.id.apkProgress)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.startScanButton).setOnClickListener { startScan() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        startScan()
    }

    private fun startScan() {
        progress.visibility = View.VISIBLE
        status.text = "正在扫描存储中的安装包…"
        adapter.submit(emptyList())

        lifecycleScope.launch {
            val findings = AppContainer.apkScan.scanCommonDirs()
            progress.visibility = View.GONE
            adapter.submit(findings)
            status.text = if (findings.isEmpty()) {
                "未发现可疑安装包"
            } else {
                "发现 ${findings.size} 个需关注的安装包"
            }
        }
    }
}
