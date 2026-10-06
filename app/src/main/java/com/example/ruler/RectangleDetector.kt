package com.example.ruler

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 简易矩形检测器：用于自动识别画面中的参考物（矩形物体）。
 *
 * 算法：灰度化 → 高斯模糊 → 自适应阈值二值化 → 轮廓追踪 → 四边形拟合。
 * 返回检测到的矩形四个顶点（相对图像宽高的归一化坐标 0~1）。
 */
object RectangleDetector {

    data class Rect(val points: List<PointF>) {
        val width: Float get() = distance(points[0], points[1])
        val height: Float get() = distance(points[1], points[2])
        val area: Float get() = width * height
    }

    data class PointF(val x: Float, val y: Float)

    private fun distance(a: PointF, b: PointF) =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    /**
     * 从 Bitmap 中检测最大的矩形。
     * @return 归一化的矩形顶点，或 null（未检测到）。
     */
    fun detect(bitmap: Bitmap): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        // 缩小处理以提升速度
        val scale = 1f
        val gray = toGrayscale(bitmap)
        val blurred = boxBlur(gray, w, h, 3)
        val binary = adaptiveThreshold(blurred, w, h, 15, 8)
        val contours = findContours(binary, w, h)
        // 找最接近矩形的最大轮廓
        var best: Rect? = null
        var bestArea = 0f
        for (contour in contours) {
            if (contour.size < 4) continue
            val rect = fitQuadrilateral(contour) ?: continue
            // 检查是否为凸四边形且面积足够大
            val area = polygonArea(rect.points)
            if (area < w * h * 0.02f) continue
            if (isConvex(rect.points) && area > bestArea) {
                bestArea = area
                best = rect
            }
        }
        // 归一化
        return best?.let {
            Rect(it.points.map { p -> PointF(p.x / w * scale, p.y / h * scale) })
        }
    }

    private fun toGrayscale(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            gray[i] = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
        }
        return gray
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val dst = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            sum += src[ny * w + nx]
                            count++
                        }
                    }
                }
                dst[y * w + x] = sum / count
            }
        }
        return dst
    }

    private fun adaptiveThreshold(src: IntArray, w: Int, h: Int, blockSize: Int, c: Int): BooleanArray {
        val dst = BooleanArray(src.size)
        val half = blockSize / 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -half..half) {
                    for (dx in -half..half) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            sum += src[ny * w + nx]
                            count++
                        }
                    }
                }
                val mean = sum / count
                dst[y * w + x] = src[y * w + x] < mean - c
            }
        }
        return dst
    }

    // 简化版轮廓查找：扫描边缘点
    private fun findContours(binary: BooleanArray, w: Int, h: Int): List<List<PointF>> {
        val visited = BooleanArray(binary.size)
        val contours = mutableListOf<List<PointF>>()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (binary[idx] && !visited[idx]) {
                    val contour = traceContour(binary, visited, w, h, x, y)
                    if (contour.size > 10) contours.add(contour)
                }
            }
        }
        return contours
    }

    private fun traceContour(
        binary: BooleanArray, visited: BooleanArray,
        w: Int, h: Int, sx: Int, sy: Int
    ): List<PointF> {
        val contour = mutableListOf<PointF>()
        var x = sx
        var y = sy
        val dirs = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
            intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1)
        )
        var dir = 0
        var steps = 0
        while (steps < w * h) {
            val idx = y * w + x
            if (idx in binary.indices) visited[idx] = true
            contour.add(PointF(x.toFloat(), y.toFloat()))
            var found = false
            for (i in 0 until 8) {
                val nd = (dir + i) % 8
                val nx = x + dirs[nd][0]
                val ny = y + dirs[nd][1]
                if (nx in 0 until w && ny in 0 until h) {
                    val nidx = ny * w + nx
                    if (binary[nidx] && !visited[nidx]) {
                        x = nx
                        y = ny
                        dir = (nd + 6) % 8
                        found = true
                        break
                    }
                }
            }
            if (!found) break
            if (x == sx && y == sy && contour.size > 4) break
            steps++
        }
        return contour
    }

    // 用 Douglas-Peucker 简化轮廓为四边形
    private fun fitQuadrilateral(contour: List<PointF>): Rect? {
        // 简化：取轮廓上最远的4个点
        if (contour.size < 4) return null
        // 找到边界框四角附近的点
        val top = contour.minByOrNull { it.y }!!
        val bottom = contour.maxByOrNull { it.y }!!
        val left = contour.minByOrNull { it.x }!!
        val right = contour.maxByOrNull { it.x }!!
        val points = listOf(top, right, bottom, left)
        // 排序：按顺时针
        return Rect(sortClockwise(points))
    }

    private fun sortClockwise(pts: List<PointF>): List<PointF> {
        val cx = pts.map { it.x }.average().toFloat()
        val cy = pts.map { it.y }.average().toFloat()
        return pts.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
    }

    private fun polygonArea(pts: List<PointF>): Float {
        var area = 0f
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i].x * pts[j].y
            area -= pts[j].x * pts[i].y
        }
        return abs(area) / 2f
    }

    private fun isConvex(pts: List<PointF>): Boolean {
        val n = pts.size
        if (n < 4) return false
        var sign = 0
        for (i in 0 until n) {
            val dx1 = pts[(i + 1) % n].x - pts[i].x
            val dy1 = pts[(i + 1) % n].y - pts[i].y
            val dx2 = pts[(i + 2) % n].x - pts[(i + 1) % n].x
            val dy2 = pts[(i + 2) % n].y - pts[(i + 1) % n].y
            val cross = dx1 * dy2 - dy1 * dx2
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s
                else if (sign != s) return false
            }
        }
        return true
    }
}
