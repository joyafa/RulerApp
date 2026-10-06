package com.example.ruler

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * 相机测量界面（改进版）。
 *
 * 流程：
 * 1. 默认进入测量模式。若未校准，画面底部显示黄色提示"未校准"。
 * 2. 点「校准」→ 进入校准模式 → 在画面上拖两个橙点对齐已知物体两端
 *    → 点快捷按钮（信用卡 85.6mm / 身份证 / A4 / 自定义）完成校准 → 自动回到测量模式。
 * 3. 测量模式下：点画面设起点（绿），再点设终点（红），立即显示距离。
 *    可拖动端点微调；点空白处重新测量。
 */
class CameraMeasureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: MeasureOverlayView
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnAddSegment: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnPhoto: MaterialButton
    private lateinit var calibBar: LinearLayout
    private lateinit var tvCalibStatus: TextView
    private lateinit var tvTotal: TextView

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    private var lastMeasuredCm = 0f
    private var totalCm = 0f
    private var segmentCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_measure)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnReset = findViewById(R.id.btnReset)
        btnAddSegment = findViewById(R.id.btnAddSegment)
        btnSave = findViewById(R.id.btnSave)
        btnPhoto = findViewById(R.id.btnPhoto)
        calibBar = findViewById(R.id.calibBar)
        tvCalibStatus = findViewById(R.id.tvCalibStatus)
        tvTotal = findViewById(R.id.tvTotal)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 读取已保存的校准值
        val savedMmPerPx = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(KEY_MM_PER_PX, 0f)
        if (savedMmPerPx > 0f) overlay.mmPerPx = savedMmPerPx
        updateCalibStatus()

        // 默认测量模式
        overlay.mode = MeasureOverlayView.Mode.MEASURE

        overlay.onMeasureResult = { mm ->
            lastMeasuredCm = mm / 10f
        }

        btnCalibrate.setOnClickListener { toggleCalibrate() }
        btnReset.setOnClickListener { resetAll() }
        btnAddSegment.setOnClickListener { addSegment() }
        btnSave.setOnClickListener { saveMeasurement() }
        btnPhoto.setOnClickListener { takePhoto() }

        // 参考物快捷按钮
        findViewById<MaterialButton>(R.id.btnCreditCard).setOnClickListener { finishCalibration(85.6f) }
        findViewById<MaterialButton>(R.id.btnIDCard).setOnClickListener { finishCalibration(85.6f) }
        findViewById<MaterialButton>(R.id.btnA4).setOnClickListener { finishCalibration(210f) }
        findViewById<MaterialButton>(R.id.btnCustomLen).setOnClickListener { showCustomLengthDialog() }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_CAMERA)
    }

    // ---------- 校准 ----------
    private fun toggleCalibrate() {
        if (overlay.mode == MeasureOverlayView.Mode.MEASURE) {
            // 进入校准模式
            overlay.mode = MeasureOverlayView.Mode.CALIBRATE
            overlay.resetCalibration()
            calibBar.visibility = View.VISIBLE
            btnCalibrate.text = "完成校准"
            btnCalibrate.setBackgroundColor(0xFF4CAF50.toInt())
        } else {
            // 退出校准模式（不保存）
            overlay.mode = MeasureOverlayView.Mode.MEASURE
            calibBar.visibility = View.GONE
            btnCalibrate.text = "校准"
            btnCalibrate.setBackgroundColor(0xFF6200EE.toInt())
        }
    }

    /** 用给定的实际长度（mm）完成校准 */
    private fun finishCalibration(actualMm: Float) {
        val s = overlay.refStart
        val e = overlay.refEnd
        if (s == null || e == null) {
            Toast.makeText(this, "请先在画面上拖出两个参考点", Toast.LENGTH_SHORT).show()
            return
        }
        val pxDist = hypot(e.x - s.x, e.y - s.y)
        if (pxDist < 10f) {
            Toast.makeText(this, "参考点距离过小，请拉大一些", Toast.LENGTH_SHORT).show()
            return
        }
        val mmPerPx = actualMm / pxDist
        overlay.mmPerPx = mmPerPx
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_MM_PER_PX, mmPerPx).apply()
        Toast.makeText(this, "校准完成：%.4f mm/px".format(mmPerPx), Toast.LENGTH_SHORT).show()
        // 自动切回测量模式
        overlay.mode = MeasureOverlayView.Mode.MEASURE
        overlay.resetMeasure()
        calibBar.visibility = View.GONE
        btnCalibrate.text = "校准"
        btnCalibrate.setBackgroundColor(0xFF6200EE.toInt())
        updateCalibStatus()
    }

    private fun showCustomLengthDialog() {
        val et = EditText(this).apply {
            hint = "输入参考物实际长度(mm)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("自定义参考物长度")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val v = et.text.toString().toFloatOrNull() ?: 0f
                if (v > 0f) finishCalibration(v)
                else Toast.makeText(this, "请输入有效长度", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateCalibStatus() {
        if (overlay.mmPerPx > 0f) {
            tvCalibStatus.text = "● 已校准"
            tvCalibStatus.setTextColor(0xFF81C784.toInt())
        } else {
            tvCalibStatus.text = "● 未校准"
            tvCalibStatus.setTextColor(0xFFFFD54F.toInt())
        }
    }

    // ---------- 测量操作 ----------
    private fun addSegment() {
        if (lastMeasuredCm <= 0f) {
            Toast.makeText(this, "请先完成一次测量", Toast.LENGTH_SHORT).show()
            return
        }
        totalCm += lastMeasuredCm
        segmentCount++
        tvTotal.visibility = View.VISIBLE
        tvTotal.text = "累计 %d 段: %.2f cm".format(segmentCount, totalCm)
        overlay.resetMeasure()
        lastMeasuredCm = 0f
    }

    private fun saveMeasurement() {
        if (lastMeasuredCm <= 0f) {
            Toast.makeText(this, "请先完成一次测量", Toast.LENGTH_SHORT).show()
            return
        }
        MeasurementStore.save(this, "camera", lastMeasuredCm)
        Toast.makeText(this, "已保存到历史记录", Toast.LENGTH_SHORT).show()
    }

    private fun resetAll() {
        overlay.resetMeasure()
        totalCm = 0f
        segmentCount = 0
        lastMeasuredCm = 0f
        tvTotal.visibility = View.GONE
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "Ruler_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Ruler")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build()
        capture.takePicture(
            outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        Toast.makeText(this@CameraMeasureActivity, "已保存到相册: $name", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@CameraMeasureActivity, "拍照失败: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "相机启动失败: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQ_CAMERA = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val PREFS = "camera_ruler_prefs"
        private const val KEY_MM_PER_PX = "mm_per_px"
    }
}
