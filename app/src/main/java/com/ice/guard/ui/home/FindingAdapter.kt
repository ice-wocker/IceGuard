package com.ice.guard.ui.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.report.ScanReport
import com.ice.guard.core.rules.RiskLevel

/**
 * 体检发现项列表适配器。
 * 自研实现，仅使用 RecyclerView 官方基类，无第三方 adapter 库。
 */
class FindingAdapter(
    private var items: List<ScanReport.ModuleResult> = emptyList()
) : RecyclerView.Adapter<FindingAdapter.ModuleViewHolder>() {

    fun submit(modules: List<ScanReport.ModuleResult>) {
        items = modules
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModuleViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_module, parent, false)
        return ModuleViewHolder(v)
    }

    override fun onBindViewHolder(holder: ModuleViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ModuleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.moduleTitle)
        private val score: TextView = itemView.findViewById(R.id.moduleScore)
        private val summary: TextView = itemView.findViewById(R.id.moduleSummary)
        private val detail: TextView = itemView.findViewById(R.id.moduleDetail)
        private val badge: TextView = itemView.findViewById(R.id.riskBadge)

        fun bind(module: ScanReport.ModuleResult) {
            title.text = module.moduleName
            score.text = "${module.score}"

            val level = RiskLevel.fromScore(module.score)
            score.setTextColor(levelColor(level))
            badge.text = level.label
            badge.backgroundTintList = ColorStateList.valueOf(levelColor(level))

            if (module.findings.isEmpty()) {
                summary.text = "未发现问题"
                summary.setTextColor(itemView.context.getColor(R.color.risk_safe))
                detail.visibility = View.GONE
            } else {
                summary.text = "发现 ${module.findings.size} 项需关注"
                summary.setTextColor(itemView.context.getColor(R.color.text_secondary))
                detail.text = module.findings.take(5).joinToString("\n") { "· ${it.title}：${it.detail}" }
                detail.visibility = View.VISIBLE
            }

            // 点击展开/收起详情
            itemView.setOnClickListener {
                detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        private fun levelColor(level: RiskLevel): Int {
            val resId = when (level) {
                RiskLevel.SAFE -> R.color.risk_safe
                RiskLevel.LOW -> R.color.risk_low
                RiskLevel.MEDIUM -> R.color.risk_medium
                RiskLevel.HIGH -> R.color.risk_high
                RiskLevel.CRITICAL -> R.color.risk_critical
            }
            return itemView.context.getColor(resId)
        }
    }
}
