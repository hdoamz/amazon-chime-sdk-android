package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.graphics.Point
import android.view.View

/*
 *  Copyright 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */




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
private const val BALANCED_VISIBLE_FRACTION = 0.5625f



/**
 * Static helper functions for renderer implementations.
 */
object RendererCommon {

    /**
     * Helper class for determining layout size based on layout requirements, scaling type, and video
     * aspect ratio.
     */
    class VideoLayoutMeasure {
        // The scaling type determines how the video will fill the allowed layout area in measure(). It
        // can be specified separately for the case when video has matched orientation with layout size
        // and when there is an orientation mismatch.
        private var visibleFractionMatchOrientation: Float =
            convertScalingTypeToVisibleFraction(ScalingType.SCALE_ASPECT_BALANCED)
        private var visibleFractionMismatchOrientation: Float =
            convertScalingTypeToVisibleFraction(ScalingType.SCALE_ASPECT_BALANCED)

        fun setScalingType(scalingType: ScalingType?) {
            setScalingType( /* scalingTypeMatchOrientation= */scalingType,  /* scalingTypeMismatchOrientation= */
                scalingType
            )
        }

        fun setScalingType(
            scalingTypeMatchOrientation: ScalingType?, scalingTypeMismatchOrientation: ScalingType?
        ) {
            visibleFractionMatchOrientation =
                scalingTypeMatchOrientation?.let { convertScalingTypeToVisibleFraction(it) }!!
            visibleFractionMismatchOrientation =
                scalingTypeMismatchOrientation?.let { convertScalingTypeToVisibleFraction(it) }!!
        }

        fun setVisibleFraction(
            visibleFractionMatchOrientation: Float,
            visibleFractionMismatchOrientation: Float
        ) {
            this.visibleFractionMatchOrientation = visibleFractionMatchOrientation
            this.visibleFractionMismatchOrientation = visibleFractionMismatchOrientation
        }

        fun measure(
            widthSpec: Int,
            heightSpec: Int,
            frameWidth: Int,
            frameHeight: Int
        ): Point {
            // Calculate max allowed layout size.
            val maxWidth = View.getDefaultSize(Int.MAX_VALUE, widthSpec)
            val maxHeight = View.getDefaultSize(Int.MAX_VALUE, heightSpec)
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
                getDisplaySize(visibleFraction, frameAspect, maxWidth, maxHeight)

            // If the measure specification is forcing a specific size - yield.
            if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.EXACTLY) {
                layoutSize.x = maxWidth
            }
            if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.EXACTLY) {
                layoutSize.y = maxHeight
            }
            return layoutSize
        }
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
        // clang-format off
        // clang-format on
        return floatArrayOf(
            values[0 * 3 + 0], values[1 * 3 + 0], 0f, values[2 * 3 + 0],
            values[0 * 3 + 1], values[1 * 3 + 1], 0f, values[2 * 3 + 1], 0f, 0f, 1f, 0f,
            values[0 * 3 + 2], values[1 * 3 + 2], 0f, values[2 * 3 + 2]
        )
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
     * Interface for rendering frames on an EGLSurface with specified viewport location. Rotation,
     * mirror, and cropping is specified using a 4x4 texture coordinate transform matrix. The frame
     * input can either be an OES texture, RGB texture, or YUV textures in I420 format. The function
     * release() must be called manually to free the resources held by this object.
     */
    interface GlDrawer {
        /**
         * Functions for drawing frames with different sources. The rendering surface target is
         * implied by the current EGL context of the calling thread and requires no explicit argument.
         * The coordinates specify the viewport location on the surface target.
         */
        fun drawOes(
            oesTextureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
            viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
        )

        fun drawRgb(
            textureId: Int,
            texMatrix: FloatArray?,
            frameWidth: Int,
            frameHeight: Int,
            viewportX: Int,
            viewportY: Int,
            viewportWidth: Int,
            viewportHeight: Int
        )

        fun drawYuv(
            yuvTextures: IntArray?,
            texMatrix: FloatArray?,
            frameWidth: Int,
            frameHeight: Int,
            viewportX: Int,
            viewportY: Int,
            viewportWidth: Int,
            viewportHeight: Int
        )

        /**
         * Release all GL resources. This needs to be done manually, otherwise resources may leak.
         */
        fun release()
    }

    /**
     * Calculate display size based on minimum fraction of the video that must remain visible,
     * video aspect ratio, and maximum display size.
     */
    fun getDisplaySize(
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
