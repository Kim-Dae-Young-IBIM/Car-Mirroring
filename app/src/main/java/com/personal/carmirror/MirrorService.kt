package com.personal.carmirror

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlin.math.abs

/**
 * MediaProjection을 들고 있다가, 차 Surface가 있고 차에서 "시작"이 눌리면 폰 화면을 차에 그린다.
 *
 * 폰 화면 → VirtualDisplay(AUTO_MIRROR) → [MirrorRenderer] → 차 Surface.
 * 렌더러가 안드로이드 오토 카드에 가리지 않는 영역 안에 비율을 유지해 그리고,
 * 그 위치를 이용해 차 화면 터치를 폰 좌표로 바꿔 [TouchInjectorService]로 입력한다.
 *
 * Android 14+에서는 MediaProjection 하나로 createVirtualDisplay를 한 번만, 그것도 권한을 받은 지
 * 5분 안에만 부를 수 있다. 그래서 권한을 받자마자 출력 없는 VirtualDisplay를 만들어 두고,
 * 미러링할 때만 렌더러 입력 Surface를 끼운다. 폰이 회전하면 resize()로 크기만 바꾼다.
 */
class MirrorService : Service(), CarInput {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var renderer: MirrorRenderer? = null
    /** 렌더러가 지금 출력하고 있는 차 Surface. */
    private var output: CarSurface? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** 알림 경과 시간 기준. 미러링이 아닐 때는 0. */
    private var mirrorStartMs = 0L
    /** VirtualDisplay 크기 (폰 화면 비율, 긴 변 최대 [MAX_CAPTURE_DIM]). */
    private var captureW = 0
    private var captureH = 0

    private val bridgeListener: () -> Unit = { sync() }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection 중지됨 (사용자/시스템)")
            shutdown()
        }
    }

    private val displayCallback = object : VirtualDisplay.Callback() {
        override fun onStopped() {
            Log.i(TAG, "VirtualDisplay 중지됨")
        }
    }

    /** 폰이 회전하면 캡처 크기를 폰 화면 비율에 맞춘다. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateCaptureSize()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        MirrorBridge.addListener(bridgeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                Log.i(TAG, "사용자가 종료 요청")
                shutdown()
            }
            else -> {
                // 시스템 재시작 등으로 권한 토큰 없이 들어온 경우: 쓸 수 있는 게 없으니 종료
                Log.w(TAG, "알 수 없는 시작 요청: ${intent?.action}")
                if (projection == null) shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // getMediaProjection 전에 반드시 mediaProjection 타입 포그라운드 서비스여야 한다 (Android 14+)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "포그라운드 서비스 시작 실패", e)
            shutdown()
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "화면 녹화 권한 결과가 유효하지 않음 (resultCode=$resultCode)")
            shutdown()
            return
        }

        // 이전 세션이 남아 있으면 정리하고 새 토큰으로 교체
        releaseProjection()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 획득 실패", e)
            null
        }
        if (mp == null) {
            shutdown()
            return
        }
        mp.registerCallback(projectionCallback, mainHandler)

        // 권한 토큰은 받은 지 5분이 지나면 createVirtualDisplay에 쓸 수 없다 (Android 14+).
        // 그래서 차 Surface가 없어도 지금 바로 출력 없이(null) 만들어 두고, 미러링할 때 Surface를 끼운다.
        try {
            val r = MirrorRenderer()
            renderer = r
            computeCaptureSize()
            r.setContentSize(captureW, captureH)
            virtualDisplay = mp.createVirtualDisplay(
                "CarMirror",
                captureW,
                captureH,
                captureDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                displayCallback,
                mainHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay/렌더러 준비 실패", e)
            projection = mp
            shutdown()
            return
        }
        projection = mp
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
        MirrorBridge.input = this
        Log.i(TAG, "MediaProjection 획득, 캡처 ${captureW}x$captureH 준비. 차 Surface 대기")
        MirrorBridge.projectionActive = true
        sync()
    }

    /** 현재 상태(권한, 차 Surface, 시작 버튼)에 맞춰 렌더러와 VirtualDisplay를 맞춘다. */
    private fun sync() {
        if (projection == null) return
        val r = renderer ?: return
        val car = MirrorBridge.carSurface?.takeIf { it.surface.isValid }

        // Android 15+는 폰이 잠기면 화면 녹화를 끊는다. 차와 연결된 동안은 화면을 켜 둬서
        // 자동 잠금을 막는다 (전원 버튼으로 직접 잠그면 막을 수 없다).
        if (car != null) acquireWakeLock() else releaseWakeLock()

        if (output != car) {
            // 차 Surface가 사라질 때는 이 호출이 끝나야 EGL이 Surface를 놓는다 (동기)
            r.setOutput(car?.surface, car?.width ?: 0, car?.height ?: 0)
            output = car
        }
        r.setArea(MirrorBridge.carArea)

        val mirror = car != null && MirrorBridge.mirroringRequested
        val vd = virtualDisplay
        if (mirror && !MirrorBridge.mirroring) {
            Log.i(TAG, "미러링 시작 (차 ${car?.width}x${car?.height}, 영역 ${MirrorBridge.carArea})")
            vd?.surface = r.inputSurface
            r.setShowContent(true)
            MirrorBridge.mirroring = true
        } else if (!mirror && MirrorBridge.mirroring) {
            Log.i(TAG, "미러링 정지")
            vd?.surface = null
            r.setShowContent(false)
            MirrorBridge.mirroring = false
        }
        updateNotification()
    }

    @Suppress("DEPRECATION")
    private fun phoneSize(): Point {
        val size = Point()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        return size
    }

    private fun computeCaptureSize() {
        val phone = phoneSize()
        val scale = minOf(1f, MAX_CAPTURE_DIM.toFloat() / maxOf(phone.x, phone.y))
        captureW = (phone.x * scale).toInt() and 1.inv()
        captureH = (phone.y * scale).toInt() and 1.inv()
    }

    private fun captureDpi(): Int {
        val phone = phoneSize()
        return (resources.displayMetrics.densityDpi * captureW.toFloat() / phone.x).toInt()
    }

    private fun updateCaptureSize() {
        val vd = virtualDisplay ?: return
        val r = renderer ?: return
        val oldW = captureW
        val oldH = captureH
        computeCaptureSize()
        if (oldW == captureW && oldH == captureH) return
        Log.i(TAG, "폰 화면 회전/크기 변경 → 캡처 ${captureW}x$captureH")
        r.setContentSize(captureW, captureH)
        vd.resize(captureW, captureH, captureDpi())
    }

    // ---- 차 화면 터치 → 폰 입력 ----

    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private val flushScroll = Runnable { dispatchScroll() }

    /** 차 Surface 좌표를 폰 화면 좌표로. 폰 화면 밖이면 null. */
    private fun toPhone(x: Float, y: Float): PointF? {
        val rect = renderer?.contentRect ?: return null
        if (!rect.contains(x.toInt(), y.toInt())) return null
        val phone = phoneSize()
        return PointF(
            (x - rect.left) / rect.width() * phone.x,
            (y - rect.top) / rect.height() * phone.y,
        )
    }

    /** 차 화면 픽셀 → 폰 화면 픽셀 배율. */
    private fun carToPhoneScale(): Float? {
        val rect = renderer?.contentRect ?: return null
        return phoneSize().x.toFloat() / rect.width()
    }

    override fun onClick(x: Float, y: Float) {
        if (!MirrorBridge.mirroring) return
        val p = toPhone(x, y) ?: return
        TouchInjectorService.tap(p)
    }

    // 스크롤은 이벤트가 잘게 여러 번 오므로 잠깐 모았다가 한 번의 드래그로 보낸다
    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!MirrorBridge.mirroring) return
        pendingScrollX += distanceX
        pendingScrollY += distanceY
        mainHandler.removeCallbacks(flushScroll)
        mainHandler.postDelayed(flushScroll, SCROLL_BATCH_MS)
    }

    private fun dispatchScroll() {
        val scale = carToPhoneScale() ?: return
        // distance는 "이전 위치 - 현재 위치"라서 손가락은 반대 방향으로 움직인 것
        val dx = -pendingScrollX * scale
        val dy = -pendingScrollY * scale
        pendingScrollX = 0f
        pendingScrollY = 0f
        swipeFromCenter(dx, dy, SCROLL_SWIPE_MS)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!MirrorBridge.mirroring) return
        mainHandler.removeCallbacks(flushScroll)
        val scale = carToPhoneScale() ?: return
        // 손가락 속도(px/s) 방향으로 짧고 빠르게 밀어서 앱이 관성 스크롤을 하게 한다
        val dx = (-pendingScrollX + velocityX * FLING_SECONDS) * scale
        val dy = (-pendingScrollY + velocityY * FLING_SECONDS) * scale
        pendingScrollX = 0f
        pendingScrollY = 0f
        swipeFromCenter(dx, dy, FLING_SWIPE_MS)
    }

    /** 폰 화면 가운데에서 (dx, dy)만큼 드래그. 화면 밖으로 나가지 않게 줄인다. */
    private fun swipeFromCenter(dx: Float, dy: Float, durationMs: Long) {
        if (dx == 0f && dy == 0f) return
        val phone = phoneSize()
        val cx = phone.x / 2f
        val cy = phone.y / 2f
        val limit = minOf(
            if (dx != 0f) (cx - EDGE_MARGIN) / abs(dx) else Float.MAX_VALUE,
            if (dy != 0f) (cy - EDGE_MARGIN) / abs(dy) else Float.MAX_VALUE,
            1f,
        )
        TouchInjectorService.swipe(
            PointF(cx, cy),
            PointF(cx + dx * limit, cy + dy * limit),
            durationMs,
        )
    }

    private fun releaseProjection() {
        // 먼저 null로 만들어 두어야 아래 상태 변경이 일으키는 sync()가 다시 연결하지 않는다
        val mp = projection
        projection = null
        if (MirrorBridge.input === this) MirrorBridge.input = null
        mainHandler.removeCallbacks(flushScroll)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        MirrorBridge.mirroring = false
        releaseWakeLock()
        virtualDisplay?.release()
        virtualDisplay = null
        renderer?.release()
        renderer = null
        output = null
        mp?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
    }

    private fun shutdown() {
        releaseProjection()
        MirrorBridge.projectionActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "서비스 종료")
        MirrorBridge.removeListener(bridgeListener)
        releaseProjection()
        MirrorBridge.projectionActive = false
        super.onDestroy()
    }

    // 폰 화면이 꺼지고 잠기면 캡처가 멈추므로 차와 연결된 동안 화면을 켜 둔다.
    // 밝기는 낮춰도 캡처 결과에는 영향이 없어서 DIM을 쓴다.
    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CarMirror:mirroring",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "화면 켜짐 유지 시작")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "화면 켜짐 유지 해제")
            }
        }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 상태별 제목·본문
        val (title, text) = when {
            MirrorBridge.mirroring -> R.string.notif_title_mirroring to R.string.notif_text_mirroring
            MirrorBridge.carSurface != null -> R.string.notif_title_car_ready to R.string.notif_text_car_ready
            else -> R.string.notif_title_ready to R.string.notif_text_ready
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(title))
            .setContentText(getString(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
        // 미러링 중에는 경과 시간을 표시한다
        if (MirrorBridge.mirroring) {
            if (mirrorStartMs == 0L) mirrorStartMs = System.currentTimeMillis()
            builder.setUsesChronometer(true).setWhen(mirrorStartMs).setShowWhen(true)
        } else {
            mirrorStartMs = 0L
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (projection == null) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            Log.w(TAG, "알림 갱신 실패 (알림 권한 없음)", e)
        }
    }

    companion object {
        const val TAG = "CarMirror"
        private const val MAX_CAPTURE_DIM = 1920
        private const val SCROLL_BATCH_MS = 80L
        private const val SCROLL_SWIPE_MS = 250L
        private const val FLING_SWIPE_MS = 60L
        private const val FLING_SECONDS = 0.08f
        private const val EDGE_MARGIN = 40f
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START = "com.personal.carmirror.START"
        private const val ACTION_STOP = "com.personal.carmirror.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, MirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, MirrorService::class.java).setAction(ACTION_STOP)
    }
}
