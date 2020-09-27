/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.content.Context
import android.graphics.Point
import android.opengl.EGLContext
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


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

    private val eglRenderer: DefaultEglVideoFrameRenderer = DefaultEglVideoFrameRenderer()
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
        eglRenderer.init(eglContext, GlRectDrawer(), logger);

    }

    override fun dispose() {
        this.logger.info(TAG, "Releasing")
        eglRenderer.release()
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        eglRenderer.releaseEglSurface();
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        surfaceWidth = 0;
        surfaceHeight = 0;
        updateSurfaceSize();

        holder?.let {
            eglRenderer.createEglSurface(it.surface)
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
        eglRenderer.setLayoutAspectRatio((right - left) / (bottom - top).toFloat())
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

        eglRenderer.render(frame)
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
}
