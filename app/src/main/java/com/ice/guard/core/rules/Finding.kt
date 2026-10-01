package com.ice.guard.core.rules

/**
 * 风险等级。按数值升序排列，便于比较与排序。
 */
enum class RiskLevel(val score: Int, val label: String) {
    SAFE(0, "安全"),
    LOW(20, "低风险"),
    MEDIUM(50, "中风险"),
    HIGH(75, "高风险"),
    CRITICAL(100, "极高风险");

    companion object {
        /**
         * 依据分值反查等级。阈值自研定义，非照搬任何现有产品。
         * 0-14 安全 / 15-39 低 / 40-64 中 / 65-84 高 / 85-100 极高
         */
        fun fromScore(score: Int): RiskLevel = when {
            score <= 0 -> SAFE
            score < 15 -> SAFE
            score < 40 -> LOW
            score < 65 -> MEDIUM
            score < 85 -> HIGH
            else -> CRITICAL
        }
    }
}

/**
 * 单条风险发现项。这是报告的最小单元。
 */
data class Finding(
    val id: String,
    val title: String,
    val detail: String,
    val level: RiskLevel,
    val weight: Int,
    val category: Category
) {
    enum class Category(val display: String) {
        PERMISSION("权限"),
        APK("安装包"),
        ENVIRONMENT("环境"),
        NETWORK("网络"),
        STORAGE("存储")
    }

    /** 用于列表展示的一行摘要 */
    fun summary(): String = "[${level.label}] $title"
}
