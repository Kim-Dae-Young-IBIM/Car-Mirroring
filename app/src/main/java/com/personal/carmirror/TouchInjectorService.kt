package com.personal.carmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 차 화면 터치를 폰에 대신 입력하는 접근성 서비스.
 * 폰 설정 → 접근성 → 설치된 앱 → CarMirror 에서 사용자가 직접 켜야 한다.
 */
class TouchInjectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "터치 입력 서비스 연결됨")
        MirrorBridge.touchEnabledChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "터치 입력 서비스 해제됨")
        MirrorBridge.touchEnabledChanged()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun tap(p: PointF) {
        val path = Path().apply { moveTo(p.x, p.y) }
        dispatch(GestureDescription.StrokeDescription(path, 0, TAP_MS), "탭 (${p.x.toInt()}, ${p.y.toInt()})")
    }

    private fun swipe(from: PointF, to: PointF, durationMs: Long) {
        val path = Path().apply {
            moveTo(from.x, from.y)
            lineTo(to.x, to.y)
        }
        dispatch(
            GestureDescription.StrokeDescription(path, 0, durationMs),
            "스와이프 (${from.x.toInt()},${from.y.toInt()})→(${to.x.toInt()},${to.y.toInt()}) ${durationMs}ms",
        )
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription, label: String) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, null, null)
        Log.d(TAG, "$label → ${if (ok) "전달" else "거부"}")
    }

    companion object {
        private const val TAG = MirrorService.TAG
        private const val TAP_MS = 50L

        @Volatile
        private var instance: TouchInjectorService? = null

        val isRunning: Boolean get() = instance != null

        fun tap(p: PointF) {
            val service = instance ?: return warnOff()
            service.tap(p)
        }

        fun swipe(from: PointF, to: PointF, durationMs: Long) {
            val service = instance ?: return warnOff()
            service.swipe(from, to, durationMs)
        }

        private fun warnOff() {
            Log.w(TAG, "터치 입력 서비스가 꺼져 있음")
        }

        /** 설정에서 켜져 있는지 (서비스가 아직 연결되지 않았어도). */
        fun isEnabledInSettings(context: Context): Boolean {
            val me = ComponentName(context, TouchInjectorService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
