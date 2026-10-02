package com.ice.guard.ui.audit

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.rules.RiskLevel
import com.ice.guard.core.scanner.PermissionAuditEngine

/**
 * 应用审计列表适配器。按风险分降序展示。
 */
class AppAuditAdapter(
    private var items: List<PermissionAuditEngine.AppAuditResult> = emptyList(),
    /** 点击「处置」时回调；由 Activity 负责弹确认框与执行 */
    private val onDispose: (PermissionAuditEngine.AppAuditResult) -> Unit = {}
) : RecyclerView.Adapter<AppAuditAdapter.VH>() {

    fun submit(data: List<PermissionAuditEngine.AppAuditResult>) {
        items = data
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_audit, parent, false)
        return VH(v, onDispose)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class VH(
        itemView: View,
        private val onDispose: (PermissionAuditEngine.AppAuditResult) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.appName)
        private val pkg: TextView = itemView.findViewById(R.id.appPackage)
        private val score: TextView = itemView.findViewById(R.id.appScore)
        private val badge: TextView = itemView.findViewById(R.id.appBadge)
        private val detail: TextView = itemView.findViewById(R.id.appDetail)
        private val dispose: TextView = itemView.findViewById(R.id.appDispose)

        fun bind(item: PermissionAuditEngine.AppAuditResult) {
            name.text = item.appLabel
            pkg.text = item.packageName
            score.text = item.score.toString()

            val color = levelColor(item.level)
            score.setTextColor(color)
            badge.text = item.level.label
            badge.backgroundTintList = ColorStateList.valueOf(color)

            if (item.findings.isEmpty()) {
                detail.text = "未发现需要关注的权限"
                detail.setTextColor(itemView.context.getColor(R.color.text_tertiary))
            } else {
                detail.text = buildString {
                    append("声明权限 ${item.declaredPermissions.size} 项\n")
                    item.findings.take(6).forEach { f ->
                        append("· [${f.level.label}] ${f.title}\n")
                    }
                }
                detail.setTextColor(itemView.context.getColor(R.color.text_secondary))
            }

            itemView.setOnClickListener {
                detail.visibility =
                    if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }

            dispose.setOnClickListener { onDispose(item) }
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
