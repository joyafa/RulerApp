package com.example.ruler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 相机测量叠加层（改进版）。
 *
 * 两种模式：
 * - MEASURE（默认）：点击画面设定起点，再点击设定终点，立即显示距离。
 *   设定终点后可拖动任意一个点微调；再次点击空白处开始新测量。
 * - CALIBRATE：拖动两个橙色端点标记已知长度物体的两端。
 *
 * 校准系数 mmPerPx 由外部设置；未校准时测量结果显示为"未校准"。
 */
class MeasureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { MEASURE, CALIBRATE }

    data class Pt(var x: Float, var y: Float)

    // 测量点
    var measureStart: Pt? = null
    var measureEnd: Pt? = null

    // 校准参考点
    var refStart: Pt? = null
    var refEnd: Pt? = null

    var mode: Mode = Mode.MEASURE
        set(value) {
            field = value
            invalidate()
        }

    /** 每像素对应的毫米数，0 表示未校准 */
    var mmPerPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val touchSlop = 40f
    private var dragging: Int = 0 // 0:none 1:start 2:end

    private val measureLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val refLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        alpha = 235
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    /** 测量结果（毫米）变化回调 */
    var onMeasureResult: ((mm: Float) -> Unit)? = null
    /** 校准像素距离变化回调 */
    var onRefDistance: ((px: Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.CALIBRATE) {
            drawRefLine(canvas)
            drawCalibrateHint(canvas)
        } else {
            drawMeasureLine(canvas)
            drawMeasureHint(canvas)
        }
    }

    // ---------- 校准模式 ----------
    private fun drawRefLine(canvas: Canvas) {
        val s = refStart ?: return
        val e = refEnd ?: return
        canvas.drawLine(s.x, s.y, e.x, e.y, refLinePaint)
        drawDot(canvas, s.x, s.y, Color.parseColor("#FF9800"))
        drawDot(canvas, e.x, e.y, Color.parseColor("#FF9800"))
        val px = hypot(e.x - s.x, e.y - s.y)
        onRefDistance?.invoke(px)
        // 在线中点显示像素距离
        val midX = (s.x + e.x) / 2f
        val midY = (s.y + e.y) / 2f
        val label = "%.0f px".format(px)
        drawLabel(canvas, label, midX, midY, Color.parseColor("#E65100"))
    }

    private fun drawCalibrateHint(canvas: Canvas) {
        val hint = if (refStart == null) "点击画面设定参考物起点"
        else if (refEnd == null) "点击画面设定参考物终点"
        else "拖动橙色端点对齐已知长度物体两端"
        canvas.drawText(hint, width / 2f, height - 140f, hintPaint)
    }

    // ---------- 测量模式 ----------
    private fun drawMeasureLine(canvas: Canvas) {
        val s = measureStart ?: return
        drawDot(canvas, s.x, s.y, Color.parseColor("#4CAF50"))
        val e = measureEnd
        if (e != null) {
            canvas.drawLine(s.x, s.y, e.x, e.y, measureLinePaint)
            drawDot(canvas, e.x, e.y, Color.parseColor("#E53935"))
            val px = hypot(e.x - s.x, e.y - s.y)
            val label = if (mmPerPx > 0f) {
                val mm = px * mmPerPx
                if (mm >= 10f) "%.2f cm".format(mm / 10f) else "%.1f mm".format(mm)
            } else "未校准"
            val midX = (s.x + e.x) / 2f
            val midY = (s.y + e.y) / 2f
            drawLabel(canvas, label, midX, midY, if (mmPerPx > 0f) Color.parseColor("#E53935") else Color.parseColor("#F57C00"))
            if (mmPerPx > 0f) onMeasureResult?.invoke(px * mmPerPx)
        }
    }

    private fun drawMeasureHint(canvas: Canvas) {
        if (mmPerPx <= 0f) {
            canvas.drawText("⚠ 未校准，请先点击「校准」按钮", width / 2f, height - 100f, warnPaint)
            return
        }
        val hint = when {
            measureStart == null -> "点击画面设定测量起点"
            measureEnd == null -> "点击画面设定测量终点"
            else -> "拖动端点微调，或点击空白处重新测量"
        }
        canvas.drawText(hint, width / 2f, height - 140f, hintPaint)
    }

    // ---------- 绘制辅助 ----------
    private fun drawDot(canvas: Canvas, x: Float, y: Float, color: Int) {
        haloPaint.color = color
        haloPaint.alpha = 120
        canvas.drawCircle(x, y, 18f, haloPaint)
        dotPaint.color = color
        canvas.drawCircle(x, y, 11f, dotPaint)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, bgColor: Int) {
        val tw = labelTextPaint.measureText(text)
        labelBgPaint.color = bgColor
        labelBgPaint.alpha = 235
        canvas.drawRect(x - tw / 2 - 18f, y - 36f, x + tw / 2 + 18f, y + 14f, labelBgPaint)
        canvas.drawText(text, x, y - 2f, labelTextPaint)
    }

    // ---------- 触摸事件 ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.CALIBRATE) handleCalibrateDown(x, y)
                else handleMeasureDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 1) {
                    if (mode == Mode.CALIBRATE) refStart = Pt(x, y) else measureStart = Pt(x, y)
                    invalidate()
                } else if (dragging == 2) {
                    if (mode == Mode.CALIBRATE) refEnd = Pt(x, y) else measureEnd = Pt(x, y)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
        }
        return true
    }

    private fun handleCalibrateDown(x: Float, y: Float) {
        val dS = refStart?.let { hypot(x - it.x, y - it.y) } ?: Float.MAX_VALUE
        val dE = refEnd?.let { hypot(x - it.x, y - it.y) } ?: Float.MAX_VALUE
        when {
            dS <= touchSlop && dS <= dE -> { refStart = Pt(x, y); dragging = 1 }
            dE <= touchSlop -> { refEnd = Pt(x, y); dragging = 2 }
            refStart == null -> { refStart = Pt(x, y); dragging = 1 }
            refEnd == null -> { refEnd = Pt(x, y); dragging = 2 }
            else -> { refStart = Pt(x, y); refEnd = null; dragging = 1 }
        }
        invalidate()
    }

    private fun handleMeasureDown(x: Float, y: Float) {
        val s = measureStart
        val e = measureEnd
        // 优先判断是否拖动已有端点
        val dS = s?.let { hypot(x - it.x, y - it.y) } ?: Float.MAX_VALUE
        val dE = e?.let { hypot(x - it.x, y - it.y) } ?: Float.MAX_VALUE
        when {
            dS <= touchSlop && dS <= dE -> { dragging = 1 }
            dE <= touchSlop -> { dragging = 2 }
            s == null -> { measureStart = Pt(x, y); dragging = 1 }
            e == null -> { measureEnd = Pt(x, y); dragging = 2 }
            else -> {
                // 已有完整测量，点击空白处重新开始
                measureStart = Pt(x, y)
                measureEnd = null
                dragging = 1
            }
        }
        invalidate()
    }

    /** 重置测量点 */
    fun resetMeasure() {
        measureStart = null
        measureEnd = null
        invalidate()
    }

    /** 重置校准点 */
    fun resetCalibration() {
        refStart = null
        refEnd = null
        invalidate()
    }
}
