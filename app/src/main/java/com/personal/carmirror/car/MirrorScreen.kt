package com.personal.carmirror.car

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
 * 차 화면. NavigationTemplate의 지도 영역(Surface)을 MirrorBridge에 넘기고,
 * 액션 스트립의 "시작/정지" 버튼으로 미러링을 켜고 끈다.
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
            MirrorBridge.carSurface = null
        }

        // 2단계(터치 조작)에서 onClick / onScroll / onFling 을 구현한다.
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
