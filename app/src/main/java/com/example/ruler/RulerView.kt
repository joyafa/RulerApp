package com.example.ruler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义尺子 View。
 *
 * 绘制策略：
 * - 顶部和左侧分别绘制一把尺子，刻度间距由屏幕物理 DPI 决定，
 *   并叠加用户校准系数 calibrationFactor（用户真实长度 / 屏幕显示长度）。
 * - 支持两个可拖动测量游标（游标 A / 游标 B），实时显示两者间距。
 * - 单位支持厘米(cm) 与 英寸(in)。
 */
class RulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- 单位枚举 ----
    enum class MeasureUnit { CM, INCH }

    // ---- 画笔 ----
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val cursorAPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val cursorBPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E88E5")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#FAFAFA") }

    // ---- 校准 / 单位 ----
    /** 每毫米对应的像素数（基于屏幕 xdpi / ydpi 计算） */
    private var pxPerMmX: Float = 0f
    private var pxPerMmY: Float = 0f
    /** 校准系数：用户测得真实长度 / 屏幕显示长度。1.0 表示无需校准 */
    var calibrationFactor: Float = 1.0f
        set(value) {
            field = value
            invalidate()
        }
    var unit: MeasureUnit = MeasureUnit.CM
        set(value) {
            field = value
            invalidate()
        }

    // ---- 游标位置（像素，距离左上角原点） ----
    /** 游标 A 在 x 轴（水平方向）的像素位置 */
    var cursorAx: Float = 0f
    /** 游标 A 在 y 轴（垂直方向）的像素位置 */
    var cursorAy: Float = 0f
    var cursorBx: Float = 0f
    var cursorBy: Float = 0f

    /** 当前正在拖动的游标：0=无, 1=A, 2=B */
    private var dragging: Int = 0
    private val touchSlop = 20f

    /** 监听器：测量结果变化回调 */
    var onMeasureChanged: ((distanceCm: Float) -> Unit)? = null

    init {
        updateDpi()
    }

    private fun updateDpi() {
        val dm = resources.displayMetrics
        // xdpi / ydpi 是屏幕每英寸的物理像素数
        pxPerMmX = dm.xdpi / 25.4f
        pxPerMmY = dm.ydpi / 25.4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 初始化游标位置
        if (cursorAx == 0f && cursorAy == 0f) {
            cursorAx = w * 0.3f
            cursorAy = h * 0.4f
        }
        if (cursorBx == 0f && cursorBy == 0f) {
            cursorBx = w * 0.7f
            cursorBy = h * 0.6f
        }
        notifyMeasure()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val effectivePxPerMmX = pxPerMmX * calibrationFactor
        val effectivePxPerMmY = pxPerMmY * calibrationFactor

        // 顶部水平尺子
        drawHorizontalRuler(canvas, effectivePxPerMmX)
        // 左侧垂直尺子
        drawVerticalRuler(canvas, effectivePxPerMmY)

        // 绘制游标与连线
        drawCursors(canvas)
        // 绘制测量结果文本
        drawMeasureText(canvas, effectivePxPerMmX, effectivePxPerMmY)
    }

    /** 绘制顶部水平尺子 */
    private fun drawHorizontalRuler(canvas: Canvas, pxPerMm: Float) {
        val rulerHeight = 90f
        val showInches = unit == MeasureUnit.INCH
        // 每英寸像素
        val pxPerInch = pxPerMm * 25.4f

        if (showInches) {
            // 英寸：每 1/16 英寸一刻度
            var inch = 0.0
            while (inch * pxPerInch <= width) {
                val x = (inch * pxPerInch).toFloat()
                val sixteenth = (inch * 16).toInt()
                val tickLen = when {
                    sixteenth % 16 == 0 -> rulerHeight       // 整英寸
                    sixteenth % 8 == 0  -> rulerHeight * 0.7f // 半英寸
                    sixteenth % 4 == 0  -> rulerHeight * 0.5f // 1/4
                    sixteenth % 2 == 0  -> rulerHeight * 0.3f // 1/8
                    else                -> rulerHeight * 0.2f // 1/16
                }
                canvas.drawLine(x, 0f, x, tickLen, tickPaint)
                if (sixteenth % 16 == 0 && x > 0) {
                    val label = inch.toInt().toString()
                    canvas.drawText(label, x, rulerHeight - 8f, textPaint)
                }
                inch += 1.0 / 16.0
            }
        } else {
            // 厘米：每 mm 一刻度
            var mm = 0
            while (mm * pxPerMm <= width) {
                val x = mm * pxPerMm
                val tickLen = when {
                    mm % 10 == 0 -> rulerHeight        // 整 cm
                    mm % 5 == 0  -> rulerHeight * 0.7f // 半 cm
                    else         -> rulerHeight * 0.4f // mm
                }
                canvas.drawLine(x, 0f, x, tickLen, tickPaint)
                if (mm % 10 == 0 && mm > 0) {
                    val cm = mm / 10
                    canvas.drawText(cm.toString(), x, rulerHeight - 8f, textPaint)
                }
                mm++
            }
        }
        // 尺子下边界线
        canvas.drawLine(0f, rulerHeight, width.toFloat(), rulerHeight, tickPaint)
    }

    /** 绘制左侧垂直尺子 */
    private fun drawVerticalRuler(canvas: Canvas, pxPerMm: Float) {
        val rulerWidth = 90f
        val showInches = unit == MeasureUnit.INCH
        val pxPerInch = pxPerMm * 25.4f

        val textPaintV = Paint(textPaint).apply { textAlign = Paint.Align.LEFT }

        if (showInches) {
            var inch = 0.0
            while (inch * pxPerInch <= height) {
                val y = (inch * pxPerInch).toFloat()
                val sixteenth = (inch * 16).toInt()
                val tickLen = when {
                    sixteenth % 16 == 0 -> rulerWidth
                    sixteenth % 8 == 0  -> rulerWidth * 0.7f
                    sixteenth % 4 == 0  -> rulerWidth * 0.5f
                    sixteenth % 2 == 0  -> rulerWidth * 0.3f
                    else                -> rulerWidth * 0.2f
                }
                canvas.drawLine(0f, y, tickLen, y, tickPaint)
                if (sixteenth % 16 == 0 && y > 0) {
                    val label = inch.toInt().toString()
                    canvas.save()
                    canvas.rotate(-90f, rulerWidth * 0.4f, y)
                    canvas.drawText(label, rulerWidth * 0.4f, y + 10f, textPaintV)
                    canvas.restore()
                }
                inch += 1.0 / 16.0
            }
        } else {
            var mm = 0
            while (mm * pxPerMm <= height) {
                val y = mm * pxPerMm
                val tickLen = when {
                    mm % 10 == 0 -> rulerWidth
                    mm % 5 == 0  -> rulerWidth * 0.7f
                    else         -> rulerWidth * 0.4f
                }
                canvas.drawLine(0f, y, tickLen, y, tickPaint)
                if (mm % 10 == 0 && mm > 0) {
                    val cm = mm / 10
                    canvas.save()
                    canvas.rotate(-90f, rulerWidth * 0.4f, y)
                    canvas.drawText(cm.toString(), rulerWidth * 0.4f, y + 10f, textPaintV)
                    canvas.restore()
                }
                mm++
            }
        }
        canvas.drawLine(rulerWidth, 0f, rulerWidth, height.toFloat(), tickPaint)
    }

    /** 绘制两个游标（十字线） */
    private fun drawCursors(canvas: Canvas) {
        // 游标 A - 红色
        canvas.drawLine(cursorAx, 0f, cursorAx, height.toFloat(), cursorAPaint)
        canvas.drawLine(0f, cursorAy, width.toFloat(), cursorAy, cursorAPaint)
        // 游标 B - 蓝色
        canvas.drawLine(cursorBx, 0f, cursorBx, height.toFloat(), cursorBPaint)
        canvas.drawLine(0f, cursorBy, width.toFloat(), cursorBy, cursorBPaint)
    }

    /** 绘制两游标之间的测量结果 */
    private fun drawMeasureText(
        canvas: Canvas,
        pxPerMmX: Float,
        pxPerMmY: Float
    ) {
        val dx = cursorBx - cursorAx
        val dy = cursorBy - cursorAy
        // 使用 x 方向 DPI 作为统一基准计算物理长度
        val distMm = kotlin.math.sqrt((dx * dx) + (dy * dy)) / pxPerMmX
        val distCm = distMm / 10f
        val label = if (unit == MeasureUnit.CM) {
            String.format("%.2f cm", distCm)
        } else {
            String.format("%.3f in", distMm / 25.4f)
        }
        // 在两游标中点显示
        val midX = (cursorAx + cursorBx) / 2f
        val midY = (cursorAy + cursorBy) / 2f
        // 背景
        val textWidth = measurePaint.measureText(label)
        canvas.drawRect(
            midX - textWidth / 2f - 16f,
            midY - 36f,
            midX + textWidth / 2f + 16f,
            midY + 8f,
            Paint().apply { color = Color.parseColor("#E53935"); alpha = 220 }
        )
        canvas.drawText(label, midX, midY, measurePaint)
    }

    /** 通知测量结果变化 */
    private fun notifyMeasure() {
        val dx = cursorBx - cursorAx
        val dy = cursorBy - cursorAy
        val pxPerMm = pxPerMmX * calibrationFactor
        val distMm = kotlin.math.sqrt((dx * dx) + (dy * dy)) / pxPerMm
        onMeasureChanged?.invoke(distMm / 10f)
    }

    // ---- 触摸处理：拖动游标 ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 判断点中了哪个游标（优先 A）
                val distA = kotlin.math.hypot(x - cursorAx, y - cursorAy)
                val distB = kotlin.math.hypot(x - cursorBx, y - cursorBy)
                dragging = when {
                    distA <= touchSlop && distA <= distB -> 1
                    distB <= touchSlop -> 2
                    else -> 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 1) {
                    cursorAx = x.coerceIn(0f, width.toFloat())
                    cursorAy = y.coerceIn(0f, height.toFloat())
                    invalidate()
                    notifyMeasure()
                } else if (dragging == 2) {
                    cursorBx = x.coerceIn(0f, width.toFloat())
                    cursorBy = y.coerceIn(0f, height.toFloat())
                    invalidate()
                    notifyMeasure()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
            }
        }
        return true
    }
}
