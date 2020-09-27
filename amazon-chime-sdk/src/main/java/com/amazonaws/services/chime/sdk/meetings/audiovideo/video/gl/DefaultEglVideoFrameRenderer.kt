package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGLContext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking


class DefaultEglVideoFrameRenderer(private val frameDrawer: VideoFrameDrawer = VideoFrameDrawer()) :
    EglVideoFrameRenderer {
    private var logger: Logger? = null
    private val TAG = "EglRenderer"

    // |renderThreadHandler| is a handler for communicating with |renderThread|, and is synchronized
    // on |handlerLock|.
    private val handlerLock = Any()

    private var handler: Handler? = null

    // EGL and GL resources for drawing YUV/OES textures. After initialization, these are only
    // accessed from the render thread.
    private var eglCore: DefaultEglCore? = null
    private val surface: Any? = null

    private lateinit var drawer: GlDrawer
    private var usePresentationTimeStamp = false
    private val drawMatrix: Matrix = Matrix()

    // Pending frame to render. Serves as a queue with size 1. Synchronized on |frameLock|.
    private val frameLock = Any()

    private var pendingFrame: VideoFrame? = null

    // These variables are synchronized on |layoutLock|.
    private val layoutLock = Any()
    private var layoutAspectRatio = 0f

    // If true, mirrors the video stream horizontally.
    private val mirrorHorizontally = false

    // If true, mirrors the video stream vertically.
    private val mirrorVertically = false

    /**
     * Initialize this class, sharing resources with |sharedContext|. The custom |drawer| will be used
     * for drawing frames on the EGLSurface. This class is responsible for calling release() on
     * |drawer|. It is allowed to call init() to reinitialize the renderer after a previous
     * init()/release() cycle. If usePresentationTimeStamp is true, eglPresentationTimeANDROID will be
     * set with the frame timestamps, which specifies desired presentation time and might be useful
     * for e.g. syncing audio and video.
     */
    override fun init(
        eglContext: EGLContext,
        drawer: GlDrawer,
        usePresentationTimeStamp: Boolean,
        logger: Logger
    ) {
        val thread = HandlerThread("SurfaceTextureVideoSource")
        thread.start()
        this.handler = Handler(thread.looper)

        this.drawer = drawer
        this.usePresentationTimeStamp = usePresentationTimeStamp
        this.logger = logger

        val handler = this.handler ?: throw UnknownError("No handler in init")
        // Create EGL context on the newly created render thread. It should be possibly to create the
        // context on this thread and make it current on the render thread, but this causes failure on
        // some Marvel based JB devices. https://bugs.chromium.org/p/webrtc/issues/detail?id=6350.
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            eglCore =
                DefaultEglCore(
                    eglContext,
                    logger = logger
                )
            logger?.info(TAG, "Renderer initialized")
            surface?.let { createEglSurfaceInternal(it) }
        }
        this.logger?.info(TAG, "Renderer initialized")
    }

    /**
     * Same as above with usePresentationTimeStamp set to false.
     *
     * @see .init
     */
    override fun init(
        eglContext: EGLContext,
        drawer: GlDrawer,
        logger: Logger
    ) {
        init(eglContext, drawer,  /* usePresentationTimeStamp= */false, logger)
    }

    override fun createEglSurface(surface: Surface) {
        createEglSurfaceInternal(surface)
    }

    override fun createEglSurface(surfaceTexture: SurfaceTexture) {
        createEglSurfaceInternal(surfaceTexture)
    }

    override fun createEglSurfaceInternal(surface: Any) {
        val handler = this.handler ?: throw UnknownError("No handler in call to create EGL Surface")
        handler.post {
            if (eglCore != null && eglCore?.hasSurface() == false) {
                eglCore?.createWindowSurface(surface)
                eglCore?.makeCurrent()

                // Necessary for YUV frames with odd width.
                // GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                logger?.info(TAG, "Created window surface for EGLRenderer")

            }
        }
    }

    override fun releaseEglSurface() {
        val handler = this.handler ?: return
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL surface")

            eglCore?.makeNothingCurrent()
            eglCore?.releaseSurface()
        }
    }

    override fun render(frame: VideoFrame) {
        synchronized(frameLock) {
            if (pendingFrame != null) {
                logger?.info(TAG, "Releasing pending frame")
                pendingFrame?.release()
            }
            pendingFrame = frame
            pendingFrame?.retain()
            val handler = handler ?: throw UnknownError("No handler in render function")
            handler.post(::renderFrameOnRenderThread)
        }
    }

    override fun setLayoutAspectRatio(layoutAspectRatio: Float) {
        synchronized(layoutLock) { this.layoutAspectRatio = layoutAspectRatio }
    }
    /**
     * Block until any pending frame is returned and all GL resources released, even if an interrupt
     * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
     * should be called before the Activity is destroyed and the EGLContext is still valid. If you
     * don't call this function, the GL resources might leak.
     */
    override fun release() {
        val handler = handler ?: throw UnknownError("No handler in release")
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL resources")
            // Detach current shader program.
            GLES20.glUseProgram( /* program= */0)
            frameDrawer.release()

            eglCore?.makeNothingCurrent()
            eglCore?.release()
            eglCore = null
        }
        val renderLooper: Looper = handler.looper
        renderLooper.quitSafely()
        this.handler = null

        synchronized (frameLock) {
            if (pendingFrame != null) {
                pendingFrame?.release();
                pendingFrame = null;
            }
        }
    }

    private fun renderFrameOnRenderThread() {
        // Fetch and render |pendingFrame|.
        var frame: VideoFrame
        synchronized(frameLock) {
            if (pendingFrame == null) {
                return
            }
            frame = pendingFrame as VideoFrame
            pendingFrame = null
        }

        val frameAspectRatio =
            frame.getRotatedWidth() / frame.getRotatedHeight().toFloat()
        var drawnAspectRatio: Float
        synchronized(
            layoutLock
        ) {
            drawnAspectRatio =
                if (layoutAspectRatio != 0f) layoutAspectRatio else frameAspectRatio
        }
        val scaleX: Float
        val scaleY: Float
        if (frameAspectRatio > drawnAspectRatio) {
            scaleX = drawnAspectRatio / frameAspectRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = frameAspectRatio / drawnAspectRatio
        }
        drawMatrix.reset()
        drawMatrix.preTranslate(0.5f, 0.5f)
        drawMatrix.preScale(if (mirrorHorizontally) -1f else 1f, if (mirrorVertically) -1f else 1f)
        drawMatrix.preScale(scaleX, scaleY)
        drawMatrix.preTranslate(-0.5f, -0.5f)
        GLES20.glClearColor(1f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        eglCore?.let {
            frameDrawer.drawFrame(
                frame, drawer, drawMatrix, 0 /* viewportX */, 0 /* viewportY */,
                it.surfaceWidth(), it.surfaceHeight()
            )
            val swapBuffersStartTimeNs = System.nanoTime()
            if (usePresentationTimeStamp) {
//                    it.swapBuffers(frame.getTimestampNs())
            } else {
                it.swapBuffers()
            }
        }
        frame.release()
    }
}