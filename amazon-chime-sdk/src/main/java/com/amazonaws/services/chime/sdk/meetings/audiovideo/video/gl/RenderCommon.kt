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



/**
 * Static helper functions for renderer implementations.
 */
object RendererCommon {
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

}
