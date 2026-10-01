package com.ice.guard.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ice.guard.core.report.ScanReport
import java.io.File

/**
 * 报告导出。将 Markdown / JSON 写入应用私有目录，再经 FileProvider 分享。
 *
 * 设计说明：不使用外部存储写入权限，改用 FileProvider 授权分享，
 * 遵循最小权限原则。
 */
object ReportExporter {

    fun exportAndShare(context: Context, report: ScanReport) {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val stamp = report.timestamp
        val md = File(dir, "ice_guard_report_$stamp.md")
        val json = File(dir, "ice_guard_report_$stamp.json")

        md.writeText(report.toMarkdown())
        json.writeText(report.toJson())

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            md
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ice 防护 · 安全体检报告")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "导出体检报告"))
    }

    /** 仅落盘不分享，返回生成的文件路径列表 */
    fun exportToFiles(context: Context, report: ScanReport): List<File> {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val stamp = report.timestamp
        val md = File(dir, "ice_guard_report_$stamp.md")
        val json = File(dir, "ice_guard_report_$stamp.json")
        md.writeText(report.toMarkdown())
        json.writeText(report.toJson())
        return listOf(md, json)
    }
}
