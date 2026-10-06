package com.example.ruler

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import kotlin.math.sqrt

/**
 * AR 深度测量：利用 ARCore 检测平面，点击屏幕中心设定起点/终点，
 * 计算两个 3D 锚点之间的真实距离，无需参考物校准。
 */
class ARMeasureActivity : AppCompatActivity() {

    private var session: Session? = null
    private lateinit var arView: ARCameraView
    private lateinit var tvARResult: TextView
    private lateinit var btnSave: MaterialButton

    private var startAnchor: Anchor? = null
    private var endAnchor: Anchor? = null
    private var measureStep = 0 // 0:set start, 1:set end
    private var lastDistanceCm = 0f
    private var arAvailabilityRetries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_measure)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        arView = findViewById(R.id.arView)
        tvARResult = findViewById(R.id.tvARResult)
        btnSave = findViewById(R.id.btnSave)

        arView.setOnClickListener { onTap() }
        btnSave.setOnClickListener {
            if (lastDistanceCm > 0f) {
                MeasurementStore.save(this, "ar", lastDistanceCm)
                Toast.makeText(this, "已保存到历史记录", Toast.LENGTH_SHORT).show()
            }
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        }
    }

    private fun onTap() {
        val s = session ?: return
        val frame = try { s.update() } catch (e: Exception) { null } ?: return
        val cx = arView.width / 2f
        val cy = arView.height / 2f
        val hitResults: List<HitResult> = frame.hitTest(cx, cy)
        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val anchor = hit.createAnchor()
                if (measureStep == 0) {
                    startAnchor?.detach()
                    startAnchor = anchor
                    tvARResult.text = "已设起点，再次点击设定终点"
                    measureStep = 1
                } else {
                    endAnchor?.detach()
                    endAnchor = anchor
                    measureStep = 0
                    updateDistance()
                }
                return
            }
        }
        Toast.makeText(this, "请将手机对准平面并缓慢移动以检测", Toast.LENGTH_SHORT).show()
    }

    private fun updateDistance() {
        val s = startAnchor?.pose ?: return
        val e = endAnchor?.pose ?: return
        val dx = e.tx() - s.tx()
        val dy = e.ty() - s.ty()
        val dz = e.tz() - s.tz()
        val distM = sqrt(dx * dx + dy * dy + dz * dz)
        lastDistanceCm = distM * 100f
        tvARResult.text = if (lastDistanceCm >= 10f)
            String.format("%.2f cm", lastDistanceCm)
        else
            String.format("%.1f mm", lastDistanceCm * 10f)
    }

    override fun onResume() {
        super.onResume()

        // 权限检查
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            return
        }

        if (session == null) {
            // 先检查设备是否支持 ARCore，避免直接 requestInstall 触发废弃页面
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            when {
                availability == ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    createSession()
                }
                availability.isSupported -> {
                    // SUPPORTED_APK_TOO_OLD / SUPPORTED_NOT_INSTALLED：设备支持但需安装/更新
                    try {
                        when (ArCoreApk.getInstance().requestInstall(this, true)) {
                            ArCoreApk.InstallStatus.INSTALLED -> createSession()
                            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "ARCore 安装失败，请检查 Google Play 服务", Toast.LENGTH_LONG).show()
                        offerFallback()
                    }
                }
                availability == ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                    // 首次检查中，延迟重试（最多 3 次）
                    if (arAvailabilityRetries < 3) {
                        arAvailabilityRetries++
                        arView.postDelayed({ onResume() }, 500)
                    } else {
                        offerFallback()
                    }
                }
                else -> {
                    // UNSUPPORTED_DEVICE_NOT_CAPABLE / UNKNOWN_ERROR / UNKNOWN_TIMED_OUT
                    Toast.makeText(
                        this,
                        "此设备不支持 ARCore，无法使用 AR 测量。\n" +
                            "请改用「相机测量」功能，通过参考物校准即可。",
                        Toast.LENGTH_LONG
                    ).show()
                    offerFallback()
                }
            }
        }

        try {
            session?.resume()
            arView.onResume()
        } catch (e: Exception) {
            session = null
            Toast.makeText(this, "相机被占用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createSession() {
        try {
            session = Session(this)
            val config = Config(session!!).apply {
                // 仅在设备支持深度时启用，否则用 DISABLED 避免崩溃
                depthMode = if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                // 启用平面检测
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            session?.configure(config)
            arView.session = session
        } catch (e: UnavailableException) {
            Toast.makeText(this, "ARCore 不可用: ${e.message}", Toast.LENGTH_LONG).show()
            offerFallback()
        }
    }

    /** 提示用户改用相机测量作为替代方案 */
    private fun offerFallback() {
        tvARResult.text = "此设备不支持 AR 测量\n点击下方按钮使用相机测量"
        tvARResult.setOnClickListener {
            startActivity(Intent(this, CameraMeasureActivity::class.java))
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        arView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        startAnchor?.detach()
        endAnchor?.detach()
        session?.close()
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAM && allPermissionsGranted()) {
            recreate()
        } else if (requestCode == REQ_CAM) {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        private const val REQ_CAM = 11
    }
}
