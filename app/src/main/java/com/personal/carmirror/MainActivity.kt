package com.personal.carmirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 폰 쪽 화면: 상태 카드 + 3단계 체크리스트 + 상태별 버튼 하나 + 설정 점검.
 * 재설치·업데이트 같은 드문 작업은 [ManageActivity]로 옮겼다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var stateCard: LinearLayout
    private lateinit var stateIcon: ImageView
    private lateinit var stateHeadline: TextView
    private lateinit var stateSub: TextView
    private lateinit var cardDivider: View
    private lateinit var livePill: LinearLayout
    private lateinit var liveTime: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button
    private lateinit var primaryHint: TextView

    private lateinit var checkCard: LinearLayout
    private lateinit var checkBody: LinearLayout
    private lateinit var checkChevron: ImageView
    private lateinit var checkSummary: TextView

    private var lastDenied = false
    private var checkExpanded = false
    private var mirrorStart = 0L

    private val bridgeListener: () -> Unit = { render() }

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
                    this, MirrorService.startIntent(this, result.resultCode, data),
                )
            } else {
                Log.w(MirrorService.TAG, "화면 녹화 권한 거부됨")
                lastDenied = true
            }
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateCard = findViewById(R.id.stateCard)
        stateIcon = findViewById(R.id.stateIcon)
        stateHeadline = findViewById(R.id.stateHeadline)
        stateSub = findViewById(R.id.stateSub)
        cardDivider = findViewById(R.id.cardDivider)
        livePill = findViewById(R.id.livePill)
        liveTime = findViewById(R.id.liveTime)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        primaryHint = findViewById(R.id.primaryHint)
        checkCard = findViewById(R.id.checkCard)
        checkBody = findViewById(R.id.checkBody)
        checkChevron = findViewById(R.id.checkChevron)
        checkSummary = findViewById(R.id.checkSummary)

        findViewById<ImageView>(R.id.btnManage).setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.checkHeader).setOnClickListener {
            checkExpanded = !checkExpanded
            render()
        }
        btnSecondary.setOnClickListener { startService(MirrorService.stopIntent(this)) }
    }

    override fun onStart() {
        super.onStart()
        MirrorBridge.addListener(bridgeListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        MirrorBridge.removeListener(bridgeListener)
        super.onStop()
    }

    // ---- 상태 모델 ----

    private enum class State { NO_PERMISSION, DENIED, READY, CAR_READY, MIRRORING }

    private fun currentState(): State = when {
        MirrorBridge.mirroring -> State.MIRRORING
        MirrorBridge.projectionActive && MirrorBridge.carSurface != null -> State.CAR_READY
        MirrorBridge.projectionActive -> State.READY
        lastDenied -> State.DENIED
        else -> State.NO_PERMISSION
    }

    private fun render() {
        val state = currentState()
        renderCard(state)
        renderSteps(state)
        renderButtons(state)
        renderCheck()
    }

    private fun renderCard(state: State) {
        val (bg, onColor, icon) = when (state) {
            State.NO_PERMISSION, State.DENIED ->
                Triple(R.drawable.bg_card_primary, R.color.on_primary_container, R.drawable.ic_screen_share)
            State.READY, State.CAR_READY ->
                Triple(R.drawable.bg_card_success, R.color.on_success_container, R.drawable.ic_check)
            State.MIRRORING ->
                Triple(R.drawable.bg_card_mirroring, 0, R.drawable.ic_cast_connected)
        }
        stateCard.setBackgroundResource(bg)
        val textColor = if (state == State.MIRRORING) Color.WHITE else ContextCompat.getColor(this, onColor)
        stateHeadline.setTextColor(textColor)
        stateSub.setTextColor(textColor)
        stateIcon.setImageResource(icon)
        stateIcon.setColorFilter(if (state == State.MIRRORING) Color.WHITE else textColor)
        cardDivider.setBackgroundColor(textColor and 0x33FFFFFF)

        val (head, sub) = when (state) {
            State.NO_PERMISSION -> R.string.state_no_permission to R.string.state_no_permission_sub
            State.DENIED -> R.string.state_denied to R.string.state_denied_sub
            State.READY -> R.string.state_ready to R.string.state_ready_sub
            State.CAR_READY -> R.string.state_car_ready to R.string.state_car_ready_sub
            State.MIRRORING -> R.string.state_mirroring to R.string.state_mirroring_sub
        }
        stateHeadline.setText(head)
        stateSub.setText(sub)

        if (state == State.MIRRORING) {
            if (mirrorStart == 0L) mirrorStart = SystemClock.elapsedRealtime()
            val sec = (SystemClock.elapsedRealtime() - mirrorStart) / 1000
            liveTime.text = String.format("%d:%02d", sec / 60, sec % 60)
            livePill.visibility = View.VISIBLE
            liveTime.postDelayed({ if (MirrorBridge.mirroring) render() }, 1000)
        } else {
            mirrorStart = 0L
            livePill.visibility = View.GONE
        }
    }

    private fun renderSteps(state: State) {
        // 0 = 미완료(회색), 1 = 현재(파랑), 2 = 완료(흰/녹색)
        val perm = if (MirrorBridge.projectionActive) 2 else 1
        val car = when {
            MirrorBridge.carSurface != null -> 2
            MirrorBridge.projectionActive -> 1
            else -> 0
        }
        val mirror = when {
            MirrorBridge.mirroring -> 2
            state == State.CAR_READY -> 1
            else -> 0
        }
        val onCard = state == State.MIRRORING
        step(R.id.step1, R.string.step_permission, perm, if (perm == 2) R.string.step_done else R.string.step_need, onCard)
        step(R.id.step2, R.string.step_open_car, car, carStateLabel(car), onCard)
        step(R.id.step3, R.string.step_mirror, mirror, if (mirror == 2) R.string.step_showing else R.string.step_none, onCard)
    }

    private fun carStateLabel(level: Int) = when (level) {
        2 -> R.string.step_connected
        1 -> R.string.step_waiting
        else -> R.string.step_none
    }

    private fun step(id: Int, label: Int, level: Int, stateLabel: Int, onCard: Boolean) {
        val row = findViewById<View>(id)
        val icon = row.findViewById<ImageView>(R.id.stepIcon)
        val labelView = row.findViewById<TextView>(R.id.stepLabel)
        val stateView = row.findViewById<TextView>(R.id.stepState)
        labelView.setText(label)
        stateView.setText(stateLabel)

        val white = Color.WHITE
        val primary = ContextCompat.getColor(this, R.color.primary)
        val onSurface = ContextCompat.getColor(this, R.color.on_surface)
        val variant = ContextCompat.getColor(this, R.color.on_surface_variant)
        val disabled = ContextCompat.getColor(this, R.color.on_surface_disabled)

        when {
            onCard -> {
                icon.setImageResource(R.drawable.ic_check_circle)
                icon.setColorFilter(white)
                labelView.setTextColor(white)
                stateView.setTextColor(white and 0xCCFFFFFF.toInt())
            }
            level == 2 -> {
                icon.setImageResource(R.drawable.ic_check_circle)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.success))
                labelView.setTextColor(onSurface)
                stateView.setTextColor(variant)
            }
            level == 1 -> {
                icon.setImageResource(R.drawable.ic_radio_on)
                icon.setColorFilter(primary)
                labelView.setTextColor(onSurface)
                labelView.setTypeface(null, android.graphics.Typeface.BOLD)
                stateView.setTextColor(primary)
            }
            else -> {
                icon.setImageResource(R.drawable.ic_radio_off)
                icon.setColorFilter(disabled)
                labelView.setTextColor(disabled)
                stateView.setTextColor(disabled)
            }
        }
        if (level != 1 && !onCard) labelView.setTypeface(null, android.graphics.Typeface.NORMAL)
    }

    private fun renderButtons(state: State) {
        when (state) {
            State.NO_PERMISSION, State.DENIED -> {
                primary(if (state == State.DENIED) R.string.btn_regrant else R.string.btn_grant) { onGrantClicked() }
                btnSecondary.visibility = View.GONE
                primaryHint.visibility = View.VISIBLE
            }
            State.READY -> {
                btnPrimary.visibility = View.GONE
                primaryHint.visibility = View.GONE
                secondaryEndShare()
            }
            State.CAR_READY -> {
                primary(R.string.car_start) { MirrorBridge.mirroringRequested = true }
                primaryHint.visibility = View.GONE
                secondaryEndShare()
            }
            State.MIRRORING -> {
                primary(R.string.btn_stop_mirror, tonalError = true) { MirrorBridge.mirroringRequested = false }
                primaryHint.visibility = View.GONE
                secondaryEndShare()
            }
        }
    }

    private fun primary(text: Int, tonalError: Boolean = false, onClick: () -> Unit) {
        btnPrimary.visibility = View.VISIBLE
        btnPrimary.setText(text)
        if (tonalError) {
            btnPrimary.setBackgroundResource(R.drawable.btn_tonal_error)
            btnPrimary.setTextColor(ContextCompat.getColor(this, R.color.error))
        } else {
            btnPrimary.setBackgroundResource(R.drawable.btn_filled)
            btnPrimary.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
        }
        btnPrimary.setOnClickListener { onClick() }
    }

    private fun secondaryEndShare() {
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.setText(R.string.btn_end_share)
    }

    // ---- 설정 점검 ----

    private fun renderCheck() {
        val aaOk = SelfInstaller.readSource(this).isPlay
        val touchOk = TouchInjectorService.isRunning || TouchInjectorService.isEnabledInSettings(this)
        val lock = lockState()

        val warnings = listOf(!aaOk, lock == false, !touchOk).count { it }
        val expand = checkExpanded || warnings > 0

        checkBody.visibility = if (expand) View.VISIBLE else View.GONE
        checkChevron.rotation = if (expand) 180f else 0f

        if (warnings == 0) {
            checkSummary.setBackgroundResource(R.drawable.chip_success)
            checkSummary.setTextColor(ContextCompat.getColor(this, R.color.on_success_container))
            checkSummary.setText(R.string.check_all_ok)
        } else {
            checkSummary.setBackgroundResource(R.drawable.chip_warning)
            checkSummary.setTextColor(ContextCompat.getColor(this, R.color.on_warning_container))
            checkSummary.text = getString(R.string.check_count, warnings)
        }

        checkRow(R.id.checkAa, aaOk, R.string.check_aa_ok, R.string.check_aa_warn, R.string.check_open) {
            startActivity(Intent(this, ManageActivity::class.java))
        }
        checkRow(R.id.checkTouch, touchOk, R.string.check_touch_ok, R.string.check_touch_warn, R.string.check_open) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val lockRow = findViewById<View>(R.id.checkLock)
        when (lock) {
            true -> checkRow(R.id.checkLock, true, R.string.check_lock_ok, R.string.check_lock_warn, R.string.check_open) {}
            false -> checkRow(R.id.checkLock, false, R.string.check_lock_ok, R.string.check_lock_warn, R.string.check_open) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            null -> {
                checkRow(R.id.checkLock, true, R.string.check_lock_unknown, R.string.check_lock_warn, R.string.check_open) {}
                lockRow.findViewById<ImageView>(R.id.checkIcon)
                    .setColorFilter(ContextCompat.getColor(this, R.color.on_surface_disabled))
            }
        }
    }

    private fun checkRow(id: Int, ok: Boolean, okLabel: Int, warnLabel: Int, actionLabel: Int, onAction: () -> Unit) {
        val row = findViewById<View>(id)
        val icon = row.findViewById<ImageView>(R.id.checkIcon)
        val label = row.findViewById<TextView>(R.id.checkLabel)
        val action = row.findViewById<Button>(R.id.checkAction)
        if (ok) {
            icon.setImageResource(R.drawable.ic_check_circle)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.success))
            label.setText(okLabel)
            action.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_warning)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.warning))
            label.setText(warnLabel)
            action.visibility = View.VISIBLE
            action.setText(actionLabel)
            action.setOnClickListener { onAction() }
        }
    }

    /** true=유지됨, false=끊김, null=확인 불가 */
    private fun lockState(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true
        return try {
            Settings.Global.getInt(contentResolver, SCREEN_SHARE_PROTECTIONS_OFF, 0) != 0
        } catch (e: Exception) {
            Log.w(MirrorService.TAG, "화면 공유 보호 설정을 읽을 수 없음", e)
            null
        }
    }

    // ---- 권한 ----

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
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        screenCapture.launch(intent)
    }

    companion object {
        private const val SCREEN_SHARE_PROTECTIONS_OFF =
            "disable_screen_share_protections_for_apps_and_notifications"
    }
}
