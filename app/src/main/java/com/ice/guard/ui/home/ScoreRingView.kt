package com.ice.guard.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.ice.guard.core.rules.RiskLevel

/**
 * 自研评分环控件。
 *
 * 实现说明：不依赖任何图表库，使用 Canvas 直接绘制。
 * 包含三层：底环（描边）、进度弧（渐变着色）、中心数值文本。
 * 数值变化通过 ValueAnimator 缓动过渡，避免生硬跳变。
 */
class ScoreRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1E293B")
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#F1F5F9")
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#94A3B8")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()

    /** 目标分值 0-100 */
    private var targetScore = 0

    /** 当前动画显示分值 */
    private var displayScore = 0f

    private var animator: ValueAnimator? = null

    private var strokeWidth = 0f

    init {
        strokeWidth = dp(14f)
        trackPaint.strokeWidth = strokeWidth
        arcPaint.strokeWidth = strokeWidth
        glowPaint.strokeWidth = strokeWidth * 1.9f
    }

    /** 设置分值并播放过渡动画 */
    fun setScore(score: Int) {
        val clamped = score.coerceIn(0, 100)
        targetScore = clamped
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayScore, clamped.toFloat()).apply {
            duration = 900L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                displayScore = it.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }

    /** 直接设置，不做动画（用于初始化或列表复用） */
    fun setScoreImmediate(score: Int) {
        animator?.cancel()
        targetScore = score.coerceIn(0, 100)
        displayScore = targetScore.toFloat()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = strokeWidth
        arcRect.set(pad, pad, w - pad, h - pad)
        scorePaint.textSize = w * 0.22f
        labelPaint.textSize = w * 0.075f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (width - strokeWidth) / 2f

        // 1) 底环：完整圆
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // 2) 进度弧：从 -90° 起顺时针扫过
        val sweep = 360f * (displayScore / 100f)
        if (sweep > 0f) {
            val level = RiskLevel.fromScore(targetScore)
            val colors = riskColors(level)

            // 渐变着色：沿弧线方向由起点色过渡到终点色
            arcPaint.shader = LinearGradient(
                arcRect.left, arcRect.top, arcRect.right, arcRect.bottom,
                colors.first, colors.second, Shader.TileMode.CLAMP
            )

            // 外发光层：低透明度加粗描边，营造柔和光晕
            glowPaint.color = colors.second
            glowPaint.alpha = 40
            canvas.drawArc(arcRect, -90f, sweep, false, glowPaint)

            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
        }

        // 3) 中心文本
        val scoreText = displayScore.toInt().toString()
        val scoreY = cy + scorePaint.textSize * 0.36f
        canvas.drawText(scoreText, cx, scoreY, scorePaint)

        // 4) 副标签
        val label = if (targetScore == 0) "待体检" else RiskLevel.fromScore(targetScore).label
        canvas.drawText(label, cx, cy + scorePaint.textSize * 0.72f + labelPaint.textSize, labelPaint)
    }

    /** 风险等级 → 渐变色对（起点色，终点色） */
    private fun riskColors(level: RiskLevel): Pair<Int, Int> = when (level) {
        RiskLevel.SAFE -> Color.parseColor("#10B981") to Color.parseColor("#22D3EE")
        RiskLevel.LOW -> Color.parseColor("#3B82F6") to Color.parseColor("#22D3EE")
        RiskLevel.MEDIUM -> Color.parseColor("#F59E0B") to Color.parseColor("#FBBF24")
        RiskLevel.HIGH -> Color.parseColor("#EF4444") to Color.parseColor("#FB923C")
        RiskLevel.CRITICAL -> Color.parseColor("#DC2626") to Color.parseColor("#EF4444")
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}
