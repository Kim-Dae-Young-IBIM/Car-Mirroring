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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat

/**
 * MediaProjection을 들고 있다가, 차 Surface가 있고 차에서 "시작"이 눌리면
 * VirtualDisplay(AUTO_MIRROR)의 출력을 차 Surface로 직접 연결한다.
 *
 * Android 14+에서는 MediaProjection 하나로 createVirtualDisplay를 한 번만, 그것도 권한을 받은 지
 * 5분 안에만 부를 수 있다. 그래서 권한을 받자마자 출력 없는 VirtualDisplay를 만들어 두고,
 * 차 Surface가 생기거나 바뀌면 resize()/setSurface()로 갈아끼운다.
 * 폰 화면과 차 화면의 비율이 다르면 시스템이 가운데 정렬 + 레터박스로 맞춰 그린다.
 */
class MirrorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    /** 현재 VirtualDisplay가 그리고 있는 차 Surface. 분리 상태면 null. */
    private var attached: CarSurface? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        projection = mp

        // 권한 토큰은 받은 지 5분이 지나면 createVirtualDisplay에 쓸 수 없다 (Android 14+).
        // 그래서 차 Surface가 없어도 지금 바로 출력 없이(null) 만들어 두고, 나중에 Surface만 끼운다.
        val metrics = resources.displayMetrics
        try {
            virtualDisplay = mp.createVirtualDisplay(
                "CarMirror",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                displayCallback,
                mainHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay 생성 실패", e)
            shutdown()
            return
        }
        Log.i(TAG, "MediaProjection 획득, VirtualDisplay 준비. 차 Surface 대기")
        MirrorBridge.projectionActive = true
        sync()
    }

    /** 현재 상태(권한, 차 Surface, 시작 버튼)에 맞춰 VirtualDisplay를 연결/분리한다. */
    private fun sync() {
        if (projection == null) return
        val car = MirrorBridge.carSurface?.takeIf { it.surface.isValid }

        // Android 15+는 폰이 잠기면 화면 녹화를 끊는다. 차와 연결된 동안은 화면을 켜 둬서
        // 자동 잠금을 막는다 (전원 버튼으로 직접 잠그면 막을 수 없다).
        if (car != null) acquireWakeLock() else releaseWakeLock()

        if (car != null && MirrorBridge.mirroringRequested) {
            attach(car)
        } else {
            detach()
        }
        updateNotification()
    }

    private fun attach(car: CarSurface) {
        if (attached == car) return
        val vd = virtualDisplay ?: return
        try {
            Log.i(TAG, "차 화면 연결 ${car.width}x${car.height} @${car.dpi}dpi")
            vd.resize(car.width, car.height, car.dpi)
            vd.surface = car.surface
            attached = car
            MirrorBridge.mirroring = true
        } catch (e: Exception) {
            Log.e(TAG, "차 화면 연결 실패", e)
            detach()
        }
    }

    private fun detach() {
        if (attached != null) {
            Log.i(TAG, "VirtualDisplay 분리 (차 Surface 없음 또는 정지)")
            virtualDisplay?.surface = null
            attached = null
        }
        MirrorBridge.mirroring = false
    }

    private fun releaseProjection() {
        // 먼저 null로 만들어 두어야 detach()가 일으키는 sync()가 다시 연결하지 않는다
        val mp = projection
        projection = null
        detach()
        releaseWakeLock()
        virtualDisplay?.release()
        virtualDisplay = null
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
        val text = if (MirrorBridge.mirroring) R.string.notif_mirroring else R.string.notif_ready
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
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
