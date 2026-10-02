package com.ice.guard.ui.guard

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ice.guard.R
import com.ice.guard.core.guard.GuardEvent
import com.ice.guard.core.guard.GuardVerdict
import com.ice.guard.core.report.ScanReport

/**
 * 拦截日志列表适配器。
 * 每条记录展示：结论、目标应用、评分、触发通道、命令返回，以及一个「立即终止」入口。
 */
class InterceptLogAdapter(
    private var items: List<GuardEvent> = emptyList(),
    /** 点击「立即终止」时回调；由 Activity 负责执行与刷新 */
    private val onTerminate: (GuardEvent) -> Unit = {}
) : RecyclerView.Adapter<InterceptLogAdapter.VH>() {

    fun submit(data: List<GuardEvent>) {
        items = data
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_intercept, parent, false)
        return VH(v, onTerminate)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class VH(
        itemView: View,
        private val onTerminate: (GuardEvent) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val badge: TextView = itemView.findViewById(R.id.logBadge)
        private val title: TextView = itemView.findViewById(R.id.logTitle)
        private val score: TextView = itemView.findViewById(R.id.logScore)
        private val meta: TextView = itemView.findViewById(R.id.logMeta)
        private val detail: TextView = itemView.findViewById(R.id.logDetail)
        private val terminate: TextView = itemView.findViewById(R.id.logTerminate)

        fun bind(event: GuardEvent) {
            badge.text = event.verdict.label
            badge.backgroundTintList = ColorStateList.valueOf(colorOf(event.verdict))
            title.text = event.appLabel
            score.text = "${event.score}"
            meta.text = "${ScanReport.formatTime(event.timestamp)} · ${event.channel.label} · ${event.action}"

            detail.text = event.detail.ifBlank { "（无附加说明）" }

            // 已真正处置过的记录不再提供重复入口
            terminate.visibility = if (event.verdict.acted) View.GONE else View.VISIBLE
            terminate.setOnClickListener { onTerminate(event) }
        }

        private fun colorOf(verdict: GuardVerdict): Int {
            val resId = when (verdict) {
                GuardVerdict.FROZEN, GuardVerdict.TERMINATED -> R.color.risk_safe
                GuardVerdict.ALERTED -> R.color.risk_medium
                GuardVerdict.FAILED -> R.color.risk_high
                GuardVerdict.OBSERVED -> R.color.risk_low
            }
            return itemView.context.getColor(resId)
        }
    }
}