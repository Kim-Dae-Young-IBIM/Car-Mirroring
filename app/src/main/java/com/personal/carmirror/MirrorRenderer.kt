package com.personal.carmirror

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * VirtualDisplay가 그린 폰 화면을 받아(SurfaceTexture) 차 Surface에 다시 그린다.
 *
 * VirtualDisplay를 차 Surface에 직접 연결하면 시스템이 Surface 전체 기준으로 가운데 정렬해서,
 * 안드로이드 오토가 오른쪽에 띄우는 카드 밑으로 화면이 들어간다. 여기서 직접 그리면
 * 실제로 보이는 영역(stable/visible area) 안에 비율을 유지해 최대로 맞출 수 있고,
 * 그 위치([contentRect])를 터치 좌표 변환에도 그대로 쓴다.
 *
 * 모든 GL 작업은 전용 스레드에서 한다. 공개 메서드는 아무 스레드에서나 불러도 된다.
 */
class MirrorRenderer {

    private val thread = HandlerThread("CarMirrorGL").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private lateinit var eglConfig: EGLConfig
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)
    private lateinit var surfaceTexture: SurfaceTexture

    /** VirtualDisplay가 그릴 입력 Surface. */
    lateinit var inputSurface: Surface
        private set

    // 아래 값들은 GL 스레드에서만 바꾼다
    private var contentW = 1
    private var contentH = 1
    private var outW = 0
    private var outH = 0
    private var area: Rect? = null
    private var showContent = false
    private var hasFrame = false

    /** 차 Surface 좌표계에서 폰 화면이 그려지는 위치. 그리지 않을 때는 null. */
    @Volatile
    var contentRect: Rect? = null
        private set

    init {
        runSync { setUpGl() }
    }

    /** 폰 화면(= VirtualDisplay) 크기. VirtualDisplay를 resize할 때 같이 부른다. */
    fun setContentSize(width: Int, height: Int) = runSync {
        contentW = width
        contentH = height
        surfaceTexture.setDefaultBufferSize(width, height)
        hasFrame = false
        draw()
    }

    /**
     * 출력할 차 Surface. null이면 분리한다. Surface가 사라지기 전에 EGL이 놓아야 하므로
     * 끝날 때까지 기다린다.
     */
    fun setOutput(surface: Surface?, width: Int, height: Int) = runSync {
        releaseWindowSurface()
        if (surface != null && surface.isValid) {
            windowSurface = try {
                EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "차 Surface에 EGL 연결 실패", e)
                EGL14.EGL_NO_SURFACE
            }
            outW = width
            outH = height
            Log.i(TAG, "렌더러 출력 연결 ${width}x$height")
        } else {
            outW = 0
            outH = 0
        }
        draw()
    }

    /** 폰 화면을 맞춰 넣을 영역 (차 Surface 좌표). null이면 Surface 전체. */
    fun setArea(rect: Rect?) {
        handler.post {
            area = rect
            draw()
        }
    }

    /** false면 검은 화면만 그린다 (정지 상태). */
    fun setShowContent(show: Boolean) {
        handler.post {
            showContent = show
            draw()
        }
    }

    fun release() {
        runSync {
            releaseWindowSurface()
            surfaceTexture.release()
            inputSurface.release()
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, pbuffer)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        thread.quitSafely()
    }

    private fun setUpGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize 실패" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        )
        eglConfig = checkNotNull(configs[0]) { "EGL 설정을 찾지 못함" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        // 출력이 없을 때도 텍스처를 갱신할 수 있게 1x1 오프스크린 Surface를 둔다
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setDefaultBufferSize(contentW, contentH)
        surfaceTexture.setOnFrameAvailableListener({ onFrame() }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    private fun onFrame() {
        val target = if (windowSurface != EGL14.EGL_NO_SURFACE) windowSurface else pbuffer
        EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        hasFrame = true
        draw()
    }

    private fun draw() {
        if (windowSurface == EGL14.EGL_NO_SURFACE || outW <= 0 || outH <= 0) {
            contentRect = null
            return
        }
        EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (showContent && hasFrame) {
            val rect = fit(contentW, contentH, area ?: Rect(0, 0, outW, outH))
            contentRect = rect
            // GL의 y축은 아래에서 위로 올라간다
            GLES20.glViewport(rect.left, outH - rect.bottom, rect.width(), rect.height())
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD_POS)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } else {
            contentRect = null
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
            Log.w(TAG, "eglSwapBuffers 실패: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    private fun releaseWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
            EGL14.eglDestroySurface(eglDisplay, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
            Log.i(TAG, "렌더러 출력 분리")
        }
        contentRect = null
    }

    private fun runSync(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        error?.let { throw it }
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "셰이더 컴파일 실패: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "셰이더 링크 실패: ${GLES20.glGetProgramInfoLog(prog)}" }
        return prog
    }

    companion object {
        private const val TAG = MirrorService.TAG

        /** w×h를 비율을 유지한 채 area 안에 최대 크기로, 가운데 정렬해 넣은 위치. */
        fun fit(w: Int, h: Int, area: Rect): Rect {
            val scale = minOf(area.width().toFloat() / w, area.height().toFloat() / h)
            val fw = (w * scale).toInt()
            val fh = (h * scale).toInt()
            val left = area.left + (area.width() - fw) / 2
            val top = area.top + (area.height() - fh) / 2
            return Rect(left, top, left + fw, top + fh)
        }

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        private val QUAD_POS = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_TEX = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(values); position(0) }
    }
}
