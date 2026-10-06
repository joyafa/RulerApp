package com.example.ruler

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.ar.core.ArCoreApk

/**
 * 尺子主界面。
 *
 * 功能：
 * 1. 全屏沉浸式显示尺子（顶部水平 + 左侧垂直）。
 * 2. 两个可拖动游标 A/B，实时显示间距。
 * 3. 校准：用户输入实际长度，自动计算校准系数。
 * 4. 单位切换：厘米 / 英寸。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rulerView: RulerView
    private lateinit var tvDistance: TextView
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var btnUnit: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnCamera: MaterialButton
    private lateinit var btnAR: MaterialButton
    private lateinit var btnHistory: MaterialButton
    private lateinit var btnSaveRuler: MaterialButton

    private var lastRulerCm = 0f
    private var arSupported = false
    private val arCheckHandler = Handler(Looper.getMainLooper())
    private var arCheckRetries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 沉浸式全屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        rulerView = findViewById(R.id.rulerView)
        tvDistance = findViewById(R.id.tvDistance)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnUnit = findViewById(R.id.btnUnit)
        btnReset = findViewById(R.id.btnReset)
        btnCamera = findViewById(R.id.btnCamera)
        btnAR = findViewById(R.id.btnAR)
        btnHistory = findViewById(R.id.btnHistory)
        btnSaveRuler = findViewById(R.id.btnSaveRuler)

        btnCamera.setOnClickListener {
            startActivity(Intent(this, CameraMeasureActivity::class.java))
        }
        btnAR.setOnClickListener {
            if (arSupported) {
                startActivity(Intent(this, ARMeasureActivity::class.java))
            } else {
                // 不支持 ARCore，提示并引导到相机测量
                AlertDialog.Builder(this)
                    .setTitle("AR 测量不可用")
                    .setMessage("此设备不支持 ARCore，无法使用 AR 测量。\n\n" +
                        "推荐使用「相机测量」功能：\n" +
                        "将信用卡/身份证等参考物放在镜头下，\n" +
                        "校准后即可测量任意物体长度。")
                    .setPositiveButton("去相机测量") { _, _ ->
                        startActivity(Intent(this, CameraMeasureActivity::class.java))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        btnSaveRuler.setOnClickListener {
            if (lastRulerCm > 0f) {
                MeasurementStore.save(this, "ruler", lastRulerCm)
                Toast.makeText(this, "已保存到历史记录", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先测量", Toast.LENGTH_SHORT).show()
            }
        }

        // 读取已保存的校准系数与单位
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        rulerView.calibrationFactor = prefs.getFloat(KEY_CALIBRATION, 1.0f)
        rulerView.unit = if (prefs.getString(KEY_UNIT, "CM") == "INCH")
            RulerView.MeasureUnit.INCH else RulerView.MeasureUnit.CM
        updateUnitButtonText()

        rulerView.onMeasureChanged = { distCm ->
            lastRulerCm = distCm
            runOnUiThread {
                if (rulerView.unit == RulerView.MeasureUnit.CM) {
                    tvDistance.text = String.format("%.2f cm", distCm)
                } else {
                    tvDistance.text = String.format("%.3f in", distCm / 2.54f)
                }
            }
        }

        btnCalibrate.setOnClickListener { showCalibrationDialog() }
        btnUnit.setOnClickListener { toggleUnit() }
        btnReset.setOnClickListener { resetCalibration() }

        // 异步检测 ARCore 支持情况
        checkArAvailability()
    }

    /** 异步检测设备是否支持 ARCore，据此更新 AR 按钮状态 */
    private fun checkArAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        when {
            availability.isSupported -> {
                arSupported = true
                runOnUiThread { btnAR.text = "AR测量" }
            }
            availability == ArCoreApk.Availability.UNKNOWN_CHECKING && arCheckRetries < 5 -> {
                // 首次检查中，延迟重试
                arCheckRetries++
                arCheckHandler.postDelayed({ checkArAvailability() }, 300)
            }
            else -> {
                arSupported = false
                runOnUiThread {
                    btnAR.text = "AR测量(不可用)"
                    btnAR.alpha = 0.5f
                }
            }
        }
    }

    private fun toggleUnit() {
        rulerView.unit = when (rulerView.unit) {
            RulerView.MeasureUnit.CM -> RulerView.MeasureUnit.INCH
            RulerView.MeasureUnit.INCH -> RulerView.MeasureUnit.CM
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_UNIT, rulerView.unit.name).apply()
        updateUnitButtonText()
        rulerView.invalidate()
    }

    private fun updateUnitButtonText() {
        btnUnit.text = if (rulerView.unit == RulerView.MeasureUnit.CM) "单位: cm" else "单位: in"
    }

    private fun resetCalibration() {
        rulerView.calibrationFactor = 1.0f
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(KEY_CALIBRATION, 1.0f).apply()
        Toast.makeText(this, "已恢复默认校准", Toast.LENGTH_SHORT).show()
    }

    /**
     * 校准对话框。
     *
     * 校准原理（参考经验：必须明确系数语义）：
     *   calibrationFactor = 实际长度 / 屏幕显示长度
     *   屏幕显示长度 = 游标像素距离 / (xdpi / 25.4)
     *   由于 RulerView 中 实际像素间距 = 基础间距 * calibrationFactor，
     *   当“显示 < 实际”时，需要放大系数（>1），从而让刻度间距变大，
     *   使同一物理位置对应的显示数值增大，达到校准目的。
     */
    private fun showCalibrationDialog() {
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_calibrate, null)
        val etActual = dialogView.findViewById<EditText>(R.id.etActualLength)
        val etShown = dialogView.findViewById<EditText>(R.id.etShownLength)

        // 预填当前屏幕显示长度（基于游标 A、B 的像素距离）
        val dm = resources.displayMetrics
        val pxPerMm = dm.xdpi / 25.4f
        val dx = rulerView.cursorBx - rulerView.cursorAx
        val dy = rulerView.cursorBy - rulerView.cursorAy
        val distMm = kotlin.math.sqrt((dx * dx) + (dy * dy)) / pxPerMm
        etShown.setText(String.format("%.2f", distMm / 10f))

        AlertDialog.Builder(this)
            .setTitle("校准尺子")
            .setView(dialogView)
            .setMessage("请将物体放在两游标之间，输入物体实际长度(cm)，点击确定完成校准。")
            .setPositiveButton("确定") { _, _ ->
                val actual = etActual.text.toString().toFloatOrNull() ?: 0f
                val shown = etShown.text.toString().toFloatOrNull() ?: 0f
                if (actual <= 0f || shown <= 0f) {
                    Toast.makeText(this, "请输入有效的长度值", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 校准系数 = 显示长度 / 实际长度。
                // RulerView 中 effectivePxPerMm = basePxPerMm * calibrationFactor，
                // 读数 = 像素距离 / effectivePxPerMm。
                // 当显示 > 实际（读数偏大）时，factor > 1，刻度间距变大，读数减小，从而校准到实际值。
                val factor = shown / actual
                rulerView.calibrationFactor = factor
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putFloat(KEY_CALIBRATION, factor).apply()
                Toast.makeText(
                    this,
                    String.format("校准完成，系数=%.3f", factor),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        arCheckHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val PREFS_NAME = "ruler_prefs"
        private const val KEY_CALIBRATION = "calibration_factor"
        private const val KEY_UNIT = "unit"
    }
}
