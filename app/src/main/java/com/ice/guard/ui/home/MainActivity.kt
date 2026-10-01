package com.ice.guard.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.report.ScanReport
import com.ice.guard.core.scanner.HealthCheckCoordinator
import com.ice.guard.di.AppContainer
import com.ice.guard.ui.apk.ApkScanActivity
import com.ice.guard.ui.audit.AuditActivity
import kotlinx.coroutines.launch

/**
 * 首页：展示安全评分、发起体检、展示明细。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scoreRing: ScoreRingView
    private lateinit var checkButton: TextView
    private lateinit var progressRow: View
    private lateinit var progressText: TextView
    private lateinit var resultHeader: TextView
    private lateinit var moduleList: RecyclerView
    private lateinit var actionRow: View
    private lateinit var lastCheckTime: TextView

    private val adapter = FindingAdapter()
    private val coordinator = HealthCheckCoordinator()

    private var lastReport: ScanReport? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scoreRing = findViewById(R.id.scoreRing)
        checkButton = findViewById(R.id.checkButton)
        progressRow = findViewById(R.id.progressRow)
        progressText = findViewById(R.id.progressText)
        resultHeader = findViewById(R.id.resultHeader)
        moduleList = findViewById(R.id.moduleList)
        actionRow = findViewById(R.id.actionRow)
        lastCheckTime = findViewById(R.id.lastCheckTime)

        moduleList.layoutManager = LinearLayoutManager(this)
        moduleList.adapter = adapter

        checkButton.setOnClickListener { startCheck() }
        findViewById<View>(R.id.exportButton).setOnClickListener { exportReport() }
        findViewById<View>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<View>(R.id.auditButton).setOnClickListener {
            startActivity(Intent(this, AuditActivity::class.java))
        }
        findViewById<View>(R.id.apkButton).setOnClickListener {
            startActivity(Intent(this, ApkScanActivity::class.java))
        }

        loadLastReport()
    }

    /** 载入上次体检结果，避免每次进入都要重新扫描 */
    private fun loadLastReport() {
        val last = AppContainer.historyStore.recent(1).firstOrNull() ?: run {
            scoreRing.setScoreImmediate(0)
            lastCheckTime.text = getString(R.string.home_never_checked)
            return
        }
        lastReport = last
        renderReport(last)
    }

    private fun startCheck() {
        if (running) return
        running = true

        checkButton.isEnabled = false
        checkButton.alpha = 0.5f
        progressRow.visibility = View.VISIBLE
        progressText.text = getString(R.string.home_checking)

        lifecycleScope.launch {
            val report = coordinator.runFullCheck(includeApk = true) { p ->
                runOnUiThread {
                    progressText.text = "${p.label}  (${p.step}/${p.total})"
                }
            }
            lastReport = report
            AppContainer.historyStore.save(report)
            renderReport(report)

            progressRow.visibility = View.GONE
            checkButton.isEnabled = true
            checkButton.alpha = 1f
            running = false
        }
    }

    private fun renderReport(report: ScanReport) {
        scoreRing.setScore(report.overallScore)
        lastCheckTime.text = "上次体检：${ScanReport.formatTime(report.timestamp)}"
        adapter.submit(report.modules)
        resultHeader.visibility = View.VISIBLE
        moduleList.visibility = View.VISIBLE
        actionRow.visibility = View.VISIBLE
    }

    private fun exportReport() {
        val report = lastReport ?: return
        ReportExporter.exportAndShare(this, report)
    }

    private fun showHistory() {
        val history = AppContainer.historyStore.recent(20)
        if (history.isEmpty()) return
        val text = buildString {
            appendLine("Ice 防护 · 历史体检记录")
            appendLine()
            history.forEachIndexed { i, r ->
                appendLine("${i + 1}. ${ScanReport.formatTime(r.timestamp)}  评分 ${r.overallScore}（${r.level.label}）")
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .setNeutralButton("清空") { _, _ ->
                AppContainer.historyStore.clear()
                scoreRing.setScoreImmediate(0)
                lastCheckTime.text = getString(R.string.home_never_checked)
                resultHeader.visibility = View.GONE
                moduleList.visibility = View.GONE
                actionRow.visibility = View.GONE
            }
            .show()
    }
}
