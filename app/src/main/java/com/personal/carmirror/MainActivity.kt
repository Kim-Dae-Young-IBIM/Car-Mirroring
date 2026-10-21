package com.personal.carmirror

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** 폰 쪽 화면: 화면 녹화(MediaProjection) 권한을 받아 MirrorService를 띄운다. */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val bridgeListener: () -> Unit = { renderStatus() }
    private var lastDenied = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.w(MirrorService.TAG, "알림 권한 거부됨 (미러링은 계속 가능)")
            requestScreenCapture()
        }

    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                Log.i(MirrorService.TAG, "화면 녹화 권한 허용됨")
                lastDenied = false
                ContextCompat.startForegroundService(
                    this,
                    MirrorService.startIntent(this, result.resultCode, data),
                )
            } else {
                Log.w(MirrorService.TAG, "화면 녹화 권한 거부됨")
                lastDenied = true
            }
            renderStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btnGrant).setOnClickListener { onGrantClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(MirrorService.stopIntent(this))
        }
    }

    override fun onStart() {
        super.onStart()
        MirrorBridge.addListener(bridgeListener)
        renderStatus()
    }

    override fun onStop() {
        MirrorBridge.removeListener(bridgeListener)
        super.onStop()
    }

    private fun onGrantClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // "앱 하나만 공유" 대신 전체 화면 캡처로 고정
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        screenCapture.launch(intent)
    }

    private fun renderStatus() {
        val car = MirrorBridge.carSurface
        status.text = when {
            MirrorBridge.mirroring && car != null ->
                getString(R.string.status_mirroring, car.width, car.height)
            MirrorBridge.projectionActive && car != null -> getString(R.string.status_ready)
            MirrorBridge.projectionActive -> getString(R.string.status_waiting_car)
            lastDenied -> getString(R.string.status_denied)
            else -> getString(R.string.status_idle)
        }
    }
}
