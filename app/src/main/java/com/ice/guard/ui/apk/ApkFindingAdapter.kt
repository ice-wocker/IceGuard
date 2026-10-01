package com.ice.guard.ui.apk

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.rules.Finding
import com.ice.guard.core.rules.RiskLevel

/** APK 扫描结果适配器 */
class ApkFindingAdapter(
    private var items: List<Finding> = emptyList()
) : RecyclerView.Adapter<ApkFindingAdapter.VH>() {

    fun submit(data: List<Finding>) { items = data; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_finding, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.findingTitle)
        private val badge: TextView = itemView.findViewById(R.id.findingBadge)
        private val weight: TextView = itemView.findViewById(R.id.findingWeight)
        private val detail: TextView = itemView.findViewById(R.id.findingDetail)

        fun bind(f: Finding) {
            title.text = f.title
            weight.text = "风险分 ${f.weight}"
            badge.text = f.level.label
            badge.backgroundTintList = ColorStateList.valueOf(levelColor(f.level))
            detail.text = f.detail
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
