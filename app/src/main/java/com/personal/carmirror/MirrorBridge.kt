package com.personal.carmirror

import android.os.Looper
import android.view.Surface

/** 차 화면 그리기 영역 정보 (SurfaceContainer에서 받은 값). */
data class CarSurface(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val dpi: Int,
)

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
