package com.personal.carmirror

import android.graphics.Rect
import android.os.Looper
import android.view.Surface

/** 차 화면 그리기 영역 정보 (SurfaceContainer에서 받은 값). */
data class CarSurface(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val dpi: Int,
)

/** 차 화면 입력. 좌표와 값은 차 Surface 기준 픽셀. */
interface CarInput {
    fun onClick(x: Float, y: Float)
    fun onScroll(distanceX: Float, distanceY: Float)
    fun onFling(velocityX: Float, velocityY: Float)
}

/**
 * 안드로이드 오토 쪽(차 화면)과 폰 쪽(MirrorService)을 잇는 프로세스 내 공유 상태.
 * 모든 접근은 메인 스레드에서만 한다.
 */
object MirrorBridge {

    /** 차가 준 Surface. 차 연결이 끊기거나 Surface가 사라지면 null. */
    var carSurface: CarSurface? = null
        set(value) {
            checkMain()
            field = value
            notifyChanged()
        }

    /** 차 화면의 "시작/정지" 상태. */
    var mirroringRequested: Boolean = false
        set(value) {
            checkMain()
            if (field == value) return
            field = value
            notifyChanged()
        }

    /** MirrorService가 유효한 MediaProjection을 들고 있는지. */
    var projectionActive: Boolean = false
        set(value) {
            checkMain()
            if (field == value) return
            field = value
            notifyChanged()
        }

    /** 실제로 VirtualDisplay가 차 Surface에 그리고 있는지. */
    var mirroring: Boolean = false
        set(value) {
            checkMain()
            if (field == value) return
            field = value
            notifyChanged()
        }

    /** 차 화면에서 폰 화면을 그릴 영역 (안드로이드 오토 카드 등에 가리지 않는 부분). */
    var carArea: Rect? = null
        set(value) {
            checkMain()
            if (field == value) return
            field = value
            notifyChanged()
        }

    /** 차 화면 입력(탭/스크롤/플링)을 받는 곳. MirrorService가 등록한다. */
    var input: CarInput? = null

    /** 터치 입력 접근성 서비스가 켜지거나 꺼졌을 때. */
    fun touchEnabledChanged() {
        checkMain()
        notifyChanged()
    }

    private val listeners = linkedSetOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        checkMain()
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        checkMain()
        listeners -= listener
    }

    private fun notifyChanged() {
        // 콜백 안에서 리스너가 추가/제거될 수 있으니 복사본으로 순회
        listeners.toList().forEach { it() }
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MirrorBridge는 메인 스레드에서만 사용" }
    }
}
