package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


/**
 * Core EGL state (display, context, config).
 *
 * The EGLContext must only be attached to one thread at a time.  This class is not thread-safe.
 */
class EglCore constructor(
    sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT,
    private val logger: Logger
) {
    // Public so it can be reused
    var eglContext = EGL14.EGL_NO_CONTEXT

    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null

    private val TAG = "EglCore"

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = null
            throw RuntimeException("unable to initialize EGL14")
        }

        val config = getConfig()
        if (config != null) {
            val attrib3_list = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(
                eglDisplay, config, sharedContext,
                attrib3_list, 0
            )
            if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                Log.d(TAG, "Got GLES 3 config");
                eglConfig = config
                eglContext = context
            }
        }

        // Confirm with query.
        val values = IntArray(1)
        EGL14.eglQueryContext(
            eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
            values, 0
        )
        Log.d(
            TAG,
            "EGLContext created, client version " + values[0]
        )
    }

    /**
     * Finds a suitable EGLConfig.
     *
     * @param flags Bit flags from constructor.
     * @param version Must be 2 or 3.
     */
    private fun getConfig(): EGLConfig? {
        var renderableType = EGL14.EGL_OPENGL_ES2_BIT
        renderableType = renderableType or EGLExt.EGL_OPENGL_ES3_BIT_KHR

        // The actual surface is generally RGBA or RGBX, so situationally omitting alpha
        // doesn't really help.  It can also lead to a huge performance hit on glReadPixels()
        // when reading into a GL_RGBA buffer.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,  //EGL14.EGL_DEPTH_SIZE, 16,
            //EGL14.EGL_STENCIL_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_NONE, 0,  // placeholder for recordable [@-3]
            EGL14.EGL_NONE
        )

        val configs =
            arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay, attribList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            logger.warn(
                TAG,
                "unable to find RGB8888 / 3 EGLConfig"
            )
            return null
        }
        return configs[0]
    }

    /**
     * Discards all resources held by this class, notably the EGL context.  This must be
     * called from the thread where the context was created.
     *
     * On completion, no context will be current.
     */
    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            // Android is unusual in that it uses a reference-counted EGLDisplay.  So for
            // every eglInitialize() we need an eglTerminate().
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    protected fun finalize() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            // We're limited here -- finalizers don't run on the thread that holds
            // the EGL state, so if a surface or context is still current on another
            // thread we can't fully release it here.  Exceptions thrown from here
            // are quietly discarded.  Complain in the log file.
            logger.warn(
                TAG,
                "WARNING: EglCore was not explicitly released -- state may be leaked"
            )
            release()
        }
    }

    fun createDummyPbufferSurface() {
        createPbufferSurface(1, 1)
    }

    fun createPbufferSurface(width: Int, height: Int) {
        if (eglSurface !== EGL14.EGL_NO_SURFACE) {
            throw java.lang.RuntimeException("Already has an EGLSurface")
        }
        val surfaceAttribs =
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            throw java.lang.RuntimeException(
                "Failed to create pixel buffer surface with size " + width + "x"
                        + height + ": 0x" + Integer.toHexString(EGL14.eglGetError())
            )
        }
    }

    /**
     * Creates an EGL surface associated with a Surface.
     *
     *
     * If this is destined for MediaCodec, the EGLConfig should have the "recordable" attribute.
     */
    fun createWindowSurface(surface: Any): EGLSurface? {
        if (surface !is Surface && surface !is SurfaceTexture) {
            throw java.lang.RuntimeException("invalid surface: $surface")
        }

        // Create a window surface, and attach it to the Surface we received.
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface,
            surfaceAttribs, 0
        )
        EglCore.checkGlError("eglCreateWindowSurface")
        if (eglSurface == null) {
            throw java.lang.RuntimeException("surface was null")
        }
        return eglSurface
    }

    /**
     * Creates an EGL surface associated with an offscreen buffer.
     */
    fun createOffscreenSurface(width: Int, height: Int): EGLSurface? {
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            surfaceAttribs, 0
        )
        EglCore.checkGlError("eglCreatePbufferSurface")
        if (eglSurface == null) {
            throw java.lang.RuntimeException("surface was null")
        }
        return eglSurface
    }

    /**
     * Makes our EGL context current, using the supplied surface for both "draw" and "read".
     */
    fun makeCurrent() {
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            // called makeCurrent() before create?
            logger.warn(TAG, "NOTE: makeCurrent w/o display")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }

    }

    /**
     * Makes no context current.
     */
    fun makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        ) {
            throw java.lang.RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
     */
    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun hasSurface(): Boolean {
        return eglSurface !== EGL14.EGL_NO_SURFACE
    }

    fun surfaceWidth(): Int {
        val widthArray = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, widthArray, 0)
        return widthArray[0]
    }

    fun surfaceHeight(): Int {
        val heightArray = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, heightArray, 0)
        return heightArray[0]
    }

    /**
     * Destroys the specified surface.  Note the EGLSurface won't actually be destroyed if it's
     * still current in a context.
     */
    fun releaseSurface() {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    companion object {
        private const val SIZEOF_FLOAT = 4

        /**
         * Checks to see if a GLES error has been raised.
         */
        fun checkGlError(op: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw RuntimeException("$op: glError $error")
            }
        }

        /**
         * Allocates a direct float buffer, and populates it with the float array data.
         */
        fun createFloatBuffer(coords: FloatArray): FloatBuffer {
            // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
            val bb =
                ByteBuffer.allocateDirect(coords.size * SIZEOF_FLOAT)
            bb.order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(coords)
            fb.position(0)
            return fb
        }


        /**
         * Generate texture with standard parameters.
         */
        fun generateTexture(target: Int): Int {
            val textureArray = IntArray(1)
            GLES20.glGenTextures(1, textureArray, 0)
            val textureId = textureArray[0]
            GLES20.glBindTexture(target, textureId)
            GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
            GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
            checkGlError("generateTexture")
            return textureId
        }

        /** Converts android.graphics.Matrix to a float[16] matrix array.  */
        fun convertMatrixFromAndroidGraphicsMatrix(matrix: Matrix): FloatArray {
            val values = FloatArray(9)
            matrix.getValues(values)

            // The android.graphics.Matrix looks like this:
            // [x1 y1 w1]
            // [x2 y2 w2]
            // [x3 y3 w3]
            // We want to contruct a matrix that looks like this:
            // [x1 y1  0 w1]
            // [x2 y2  0 w2]
            // [ 0  0  1  0]
            // [x3 y3  0 w3]
            // Since it is stored in column-major order, it looks like this:
            // [x1 x2 0 x3
            //  y1 y2 0 y3
            //   0  0 1  0
            //  w1 w2 0 w3]
            return floatArrayOf(
                values[0 * 3 + 0], values[1 * 3 + 0], 0f, values[2 * 3 + 0],
                values[0 * 3 + 1], values[1 * 3 + 1], 0f, values[2 * 3 + 1], 0f, 0f, 1f, 0f,
                values[0 * 3 + 2], values[1 * 3 + 2], 0f, values[2 * 3 + 2]
            )
        }
    }
}