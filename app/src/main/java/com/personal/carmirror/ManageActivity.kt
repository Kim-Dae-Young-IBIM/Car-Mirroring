package com.personal.carmirror

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** 드물게 쓰는 유지보수: 설치 출처 고치기, 최신 빌드 설치, 접근성·잠금 설정 바로가기. */
class ManageActivity : ComponentActivity() {

    private lateinit var installSource: TextView
    private lateinit var touchStatus: TextView
    private lateinit var lockStatus: TextView
    private lateinit var btnUpdate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)

        installSource = findViewById(R.id.installSource)
        touchStatus = findViewById(R.id.touchStatus)
        lockStatus = findViewById(R.id.lockStatus)
        btnUpdate = findViewById(R.id.btnUpdate)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnReinstall).setOnClickListener { onReinstallClicked() }
        btnUpdate.setOnClickListener { onUpdateClicked() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        renderInstallSource()
        renderTouchStatus()
        renderLockStatus()
    }

    private fun renderInstallSource() {
        val source = SelfInstaller.readSource(this)
        installSource.text = getString(
            if (source.isPlay) R.string.install_ok else R.string.install_not_ok,
            source.installer ?: "-",
            source.initiator ?: "-",
        )
    }

    private fun renderTouchStatus() {
        val on = TouchInjectorService.isRunning || TouchInjectorService.isEnabledInSettings(this)
        touchStatus.setText(if (on) R.string.touch_on else R.string.touch_off)
    }

    private fun renderLockStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            lockStatus.setText(R.string.lock_ok)
            return
        }
        val disabled = try {
            Settings.Global.getInt(contentResolver, SCREEN_SHARE_PROTECTIONS_OFF, 0) != 0
        } catch (e: Exception) {
            Log.w(MirrorService.TAG, "화면 공유 보호 설정을 읽을 수 없음", e)
            null
        }
        lockStatus.setText(
            when (disabled) {
                true -> R.string.lock_ok
                false -> R.string.lock_warn
                null -> R.string.lock_unknown
            }
        )
    }

    private fun onReinstallClicked() {
        if (!SelfInstaller.ensureInstallPermission(this)) {
            toast(getString(R.string.install_need_permission))
            return
        }
        try {
            SelfInstaller.launchInstall(this, SelfInstaller.copyInstalledApk(this))
        } catch (e: Exception) {
            Log.e(MirrorService.TAG, "재설치 실패", e)
            toast(getString(R.string.install_failed, e.message))
        }
    }

    private fun onUpdateClicked() {
        if (!SelfInstaller.ensureInstallPermission(this)) {
            toast(getString(R.string.install_need_permission))
            return
        }
        btnUpdate.isEnabled = false
        toast(getString(R.string.install_downloading))
        Thread {
            val result = runCatching { SelfInstaller.downloadLatestApk(this) }
            runOnUiThread {
                btnUpdate.isEnabled = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                result
                    .onSuccess { (_, apk) -> SelfInstaller.launchInstall(this, apk) }
                    .onFailure {
                        Log.e(MirrorService.TAG, "최신 빌드 다운로드 실패", it)
                        toast(getString(R.string.install_failed, it.message))
                    }
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val SCREEN_SHARE_PROTECTIONS_OFF =
            "disable_screen_share_protections_for_apps_and_notifications"
    }
}
