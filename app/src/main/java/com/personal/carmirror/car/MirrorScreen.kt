package com.personal.carmirror.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.personal.carmirror.CarSurface
import com.personal.carmirror.MirrorBridge
import com.personal.carmirror.MirrorService.Companion.TAG
import com.personal.carmirror.R

/**
 * 차 화면. NavigationTemplate의 지도 영역(Surface)과 그 안의 보이는 영역, 터치 입력을
 * MirrorBridge에 넘기고, 액션 스트립의 "시작/정지" 버튼으로 미러링을 켜고 끈다.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext) {

    private val bridgeListener: () -> Unit = { invalidate() }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface
            if (surface == null || container.width <= 0 || container.height <= 0) {
                Log.w(TAG, "차 Surface가 유효하지 않음: $container")
                MirrorBridge.carSurface = null
                return
            }
            Log.i(TAG, "차 Surface 수신 ${container.width}x${container.height} @${container.dpi}dpi")
            MirrorBridge.carSurface = CarSurface(surface, container.width, container.height, container.dpi)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            Log.i(TAG, "차 Surface 사라짐")
            visibleArea = null
            stableArea = null
            MirrorBridge.carArea = null
            MirrorBridge.carSurface = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            Log.i(TAG, "차 화면 보이는 영역 $visibleArea")
            this@MirrorScreen.visibleArea = Rect(visibleArea)
            updateArea()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            Log.i(TAG, "차 화면 고정 영역 $stableArea")
            this@MirrorScreen.stableArea = Rect(stableArea)
            updateArea()
        }

        override fun onClick(x: Float, y: Float) {
            Log.d(TAG, "차 화면 탭 ($x, $y)")
            MirrorBridge.input?.onClick(x, y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            MirrorBridge.input?.onScroll(distanceX, distanceY)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            Log.d(TAG, "차 화면 플링 ($velocityX, $velocityY)")
            MirrorBridge.input?.onFling(velocityX, velocityY)
        }
    }

    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    /**
     * 폰 화면을 그릴 영역. 고정 영역(stable)은 버튼이 나타났다 사라져도 바뀌지 않아서
     * 화면이 들썩이지 않는다. 없으면 보이는 영역(visible)을 쓴다.
     */
    private fun updateArea() {
        MirrorBridge.carArea = stableArea?.takeUnless { it.isEmpty }
            ?: visibleArea?.takeUnless { it.isEmpty }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                MirrorBridge.addListener(bridgeListener)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(TAG, "차 화면 종료 (연결 끊김 또는 앱 전환)")
                MirrorBridge.removeListener(bridgeListener)
                MirrorBridge.mirroringRequested = false
                MirrorBridge.carArea = null
                MirrorBridge.carSurface = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val requested = MirrorBridge.mirroringRequested
        val toggle = Action.Builder()
            .setTitle(carContext.getString(if (requested) R.string.car_stop else R.string.car_start))
            .setOnClickListener { onToggle() }
            .build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(toggle).build())
            // 이동(PAN) 버튼이 있어야 호스트가 드래그(onScroll/onFling)를 앱으로 보낸다
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { isInPanMode -> Log.i(TAG, "이동 모드: $isInPanMode") }

        // 미러링 중에는 화면을 가리지 않도록 안내 카드를 숨긴다
        val message = when {
            !MirrorBridge.projectionActive -> R.string.car_msg_need_permission
            !requested -> R.string.car_msg_ready
            !MirrorBridge.mirroring -> R.string.car_msg_starting
            else -> null
        }
        if (message != null) {
            builder.setNavigationInfo(MessageInfo.Builder(carContext.getString(message)).build())
        }
        return builder.build()
    }

    private fun onToggle() {
        if (MirrorBridge.mirroringRequested) {
            Log.i(TAG, "차에서 정지")
            MirrorBridge.mirroringRequested = false
            return
        }
        if (!MirrorBridge.projectionActive) {
            CarToast.makeText(carContext, R.string.car_msg_need_permission, CarToast.LENGTH_LONG).show()
        }
        Log.i(TAG, "차에서 시작")
        // 권한이 아직 없어도 요청 상태는 기억해 두고, 폰에서 허용하면 바로 연결된다
        MirrorBridge.mirroringRequested = true
    }
}
