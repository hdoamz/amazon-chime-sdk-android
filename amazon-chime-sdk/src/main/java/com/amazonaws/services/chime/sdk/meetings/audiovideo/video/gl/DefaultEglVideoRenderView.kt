/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.opengl.EGLContext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


open class DefaultEglVideoRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle), SurfaceHolder.Callback,
    EglVideoRenderView {
    /**
     * Helper class for determining layout size based on layout requirements, scaling type, and video
     * aspect ratio.
     */
    class VideoLayoutMeasure {
        // Types of video scaling:
// SCALE_ASPECT_FIT - video frame is scaled to fit the size of the view by
//    maintaining the aspect ratio (black borders may be displayed).
// SCALE_ASPECT_FILL - video frame is scaled to fill the size of the view by
//    maintaining the aspect ratio. Some portion of the video frame may be
//    clipped.
// SCALE_ASPECT_BALANCED - Compromise between FIT and FILL. Video frame will fill as much as
// possible of the view while maintaining aspect ratio, under the constraint that at least
// |BALANCED_VISIBLE_FRACTION| of the frame content will be shown.
        enum class ScalingType {
            SCALE_ASPECT_FIT, SCALE_ASPECT_FILL, SCALE_ASPECT_BALANCED
        }

        // The minimum fraction of the frame content that will be shown for |SCALE_ASPECT_BALANCED|.
// This limits excessive cropping when adjusting display size.
        private val BALANCED_VISIBLE_FRACTION = 0.5625f


        // The scaling type determines how the video will fill the allowed layout area in measure(). It
        // can be specified separately for the case when video has matched orientation with layout size
        // and when there is an orientation mismatch.
        private var visibleFractionMatchOrientation: Float =
            convertScalingTypeToVisibleFraction(
                ScalingType.SCALE_ASPECT_BALANCED
            )
        private var visibleFractionMismatchOrientation: Float =
            convertScalingTypeToVisibleFraction(
                ScalingType.SCALE_ASPECT_BALANCED
            )

        fun setScalingType(scalingType: ScalingType?) {
            setScalingType( /* scalingTypeMatchOrientation= */scalingType,  /* scalingTypeMismatchOrientation= */
                scalingType
            )
        }

        private fun setScalingType(
            scalingTypeMatchOrientation: ScalingType?, scalingTypeMismatchOrientation: ScalingType?
        ) {
            visibleFractionMatchOrientation =
                scalingTypeMatchOrientation?.let {
                    convertScalingTypeToVisibleFraction(
                        it
                    )
                }!!
            visibleFractionMismatchOrientation =
                scalingTypeMismatchOrientation?.let {
                    convertScalingTypeToVisibleFraction(
                        it
                    )
                }!!
        }

        fun measure(
            widthSpec: Int,
            heightSpec: Int,
            frameWidth: Int,
            frameHeight: Int
        ): Point {
            // Calculate max allowed layout size.
            val maxWidth = View.getDefaultSize(Int.MAX_VALUE, widthSpec)
            val maxHeight =
                View.getDefaultSize(Int.MAX_VALUE, heightSpec)
            if (frameWidth == 0 || frameHeight == 0 || maxWidth == 0 || maxHeight == 0) {
                return Point(maxWidth, maxHeight)
            }
            // Calculate desired display size based on scaling type, video aspect ratio,
            // and maximum layout size.
            val frameAspect = frameWidth / frameHeight.toFloat()
            val displayAspect = maxWidth / maxHeight.toFloat()
            val visibleFraction =
                if (frameAspect > 1.0f == displayAspect > 1.0f) visibleFractionMatchOrientation else visibleFractionMismatchOrientation
            val layoutSize: Point =
                getDisplaySize(
                    visibleFraction,
                    frameAspect,
                    maxWidth,
                    maxHeight
                )

            // If the measure specification is forcing a specific size - yield.
            if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.EXACTLY) {
                layoutSize.x = maxWidth
            }
            if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.EXACTLY) {
                layoutSize.y = maxHeight
            }
            return layoutSize
        }

        /**
         * Each scaling type has a one-to-one correspondence to a numeric minimum fraction of the video
         * that must remain visible.
         */
        private fun convertScalingTypeToVisibleFraction(scalingType: ScalingType): Float {
            return when (scalingType) {
                ScalingType.SCALE_ASPECT_FIT -> 1.0f
                ScalingType.SCALE_ASPECT_FILL -> 0.0f
                ScalingType.SCALE_ASPECT_BALANCED -> BALANCED_VISIBLE_FRACTION
                else -> throw IllegalArgumentException()
            }
        }

        /**
         * Calculate display size based on minimum fraction of the video that must remain visible,
         * video aspect ratio, and maximum display size.
         */
        private fun getDisplaySize(
            minVisibleFraction: Float,
            videoAspectRatio: Float,
            maxDisplayWidth: Int,
            maxDisplayHeight: Int
        ): Point {
            // If there is no constraint on the amount of cropping, fill the allowed display area.
            if (minVisibleFraction == 0f || videoAspectRatio == 0f) {
                return Point(maxDisplayWidth, maxDisplayHeight)
            }
            // Each dimension is constrained on max display size and how much we are allowed to crop.
            val width = Math.min(
                maxDisplayWidth,
                Math.round(maxDisplayHeight / minVisibleFraction * videoAspectRatio)
            )
            val height = Math.min(
                maxDisplayHeight,
                Math.round(maxDisplayWidth / minVisibleFraction / videoAspectRatio)
            )
            return Point(width, height)
        }
    }

    // Accessed only on the main thread.
    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var frameRotation = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var renderHandler: Handler? = null

    // EGL and GL resources for drawing YUV/OES textures. After initialization, these are only
    // accessed from the render thread.
    private var eglCore: DefaultEglCore? = null
    private val surface: Any? = null

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

    private val frameDrawer: DefaultGlVideoFrameDrawer = DefaultGlVideoFrameDrawer()

    private val videoLayoutMeasure: VideoLayoutMeasure =
        VideoLayoutMeasure()
    private lateinit var logger: Logger
    private val TAG = "DefaultVideoRenderView"

    init {
        holder.addCallback(this)
        videoLayoutMeasure.setScalingType(VideoLayoutMeasure.ScalingType.SCALE_ASPECT_FIT)
    }

    override fun init(logger: Logger, eglContext: EGLContext) {
        this.logger = logger
        this.logger.info(TAG, "Initialized")

        rotatedFrameWidth = 0;
        rotatedFrameHeight = 0;

        val thread = HandlerThread("SurfaceTextureVideoSource")
        thread.start()
        this.renderHandler = Handler(thread.looper)

        this.logger = logger

        val handler = this.renderHandler ?: throw UnknownError("No handler in init")
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

    override fun dispose() {
        this.logger.info(TAG, "Releasing")
        releaseRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        releaseEglSurface();
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        surfaceWidth = 0;
        surfaceHeight = 0;
        updateSurfaceSize();

        holder?.let {
            createEglSurfaceInternal(it.surface)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size: Point =
            videoLayoutMeasure.measure(widthSpec, heightSpec, rotatedFrameWidth, rotatedFrameHeight)
        setMeasuredDimension(size.x, size.y)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        setLayoutAspectRatio((right - left) / (bottom - top).toFloat())
        updateSurfaceSize()
    }

    override fun onVideoFrameReceived(frame: VideoFrame) {
        if (rotatedFrameWidth != frame.getRotatedWidth()
            || rotatedFrameHeight != frame.getRotatedHeight()
            || frameRotation != frame.rotation) {
            logger.info(TAG,
                "Reporting frame resolution changed to ${frame.width}x${frame.height} with rotation ${frame.rotation}"
            )

            rotatedFrameWidth = frame.getRotatedWidth();
            rotatedFrameHeight = frame.getRotatedHeight();
            frameRotation = frame.rotation;

            CoroutineScope(Dispatchers.Main).launch {
                updateSurfaceSize();
                requestLayout();
            }
        }

        render(frame)
    }

    private fun updateSurfaceSize() {
        if (rotatedFrameWidth != 0 && rotatedFrameHeight != 0 && width != 0 && height != 0) {
            val layoutAspectRatio = width / height.toFloat()
            val frameAspectRatio: Float =
                rotatedFrameWidth.toFloat() / rotatedFrameHeight
            val drawnFrameWidth: Int
            val drawnFrameHeight: Int
            if (frameAspectRatio > layoutAspectRatio) {
                drawnFrameWidth = ((rotatedFrameHeight * layoutAspectRatio).toInt())
                drawnFrameHeight = rotatedFrameHeight
            } else {
                drawnFrameWidth = rotatedFrameWidth
                drawnFrameHeight = ((rotatedFrameWidth / layoutAspectRatio).toInt())
            }
            // Aspect ratio of the drawn frame and the view is the same.
            val width = Math.min(width, drawnFrameWidth)
            val height = Math.min(height, drawnFrameHeight)
            logger.info(
                TAG,
                "updateSurfaceSize. Layout size: " + getWidth() + "x" + getHeight() + ", frame size: "
                        + rotatedFrameWidth + "x" + rotatedFrameHeight + ", requested surface size: " + width
                        + "x" + height + ", old surface size: " + surfaceWidth + "x" + surfaceHeight
            )
            if (width != surfaceWidth || height != surfaceHeight) {
                surfaceWidth = width
                surfaceHeight = height
                holder.setFixedSize(width, height)
            }
        } else {
            surfaceHeight = 0
            surfaceWidth = surfaceHeight
            holder.setSizeFromLayout()
        }
    }

    fun createEglSurfaceInternal(surface: Any) {
        val handler = this.renderHandler ?: throw UnknownError("No handler in call to create EGL Surface")
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

    fun releaseEglSurface() {
        val handler = this.renderHandler ?: return
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL surface")

            eglCore?.makeNothingCurrent()
            eglCore?.releaseSurface()
        }
    }

    fun render(frame: VideoFrame) {
        synchronized(frameLock) {
            if (pendingFrame != null) {
                logger?.info(TAG, "Releasing pending frame")
                pendingFrame?.release()
            }
            pendingFrame = frame
            pendingFrame?.retain()
            val handler = renderHandler ?: throw UnknownError("No handler in render function")
            handler.post(::renderFrameOnRenderThread)
        }
    }

    fun setLayoutAspectRatio(layoutAspectRatio: Float) {
        synchronized(layoutLock) { this.layoutAspectRatio = layoutAspectRatio }
    }
    /**
     * Block until any pending frame is returned and all GL resources released, even if an interrupt
     * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
     * should be called before the Activity is destroyed and the EGLContext is still valid. If you
     * don't call this function, the GL resources might leak.
     */
    fun releaseRender() {
        val handler = renderHandler ?: throw UnknownError("No handler in release")
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
        this.renderHandler = null

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
                frame, drawMatrix, 0 /* viewportX */, 0 /* viewportY */,
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
