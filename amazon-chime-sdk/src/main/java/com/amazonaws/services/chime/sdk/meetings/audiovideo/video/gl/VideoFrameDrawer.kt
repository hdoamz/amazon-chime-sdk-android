package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.graphics.Point
import android.opengl.GLES20
import android.util.Log
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.GlUtil.generateTexture
import com.xodee.client.video.YuvHelper
import java.nio.ByteBuffer


/**
 * Helper class to draw VideoFrames. Calls either drawer.drawOes, drawer.drawRgb, or
 * drawer.drawYuv depending on the type of the buffer. The frame will be rendered with rotation
 * taken into account. You can supply an additional render matrix for custom transformations.
 */
class VideoFrameDrawer {
    /**
     * Helper class for uploading YUV bytebuffer frames to textures that handles stride > width. This
     * class keeps an internal ByteBuffer to avoid unnecessary allocations for intermediate copies.
     */
    private class YuvUploader {
        // Intermediate copy buffer for uploading yuv frames that are not packed, i.e. stride > width.
        // TODO(magjed): Investigate when GL_UNPACK_ROW_LENGTH is available, or make a custom shader
        // that handles stride and compare performance with intermediate copy.
        private var copyBuffer: ByteBuffer? = null

        var yuvTextures: IntArray? = null

        /**
         * Upload |planes| into OpenGL textures, taking stride into consideration.
         *
         * @return Array of three texture indices corresponding to Y-, U-, and V-plane respectively.
         */
        fun uploadYuvData(
            width: Int,
            height: Int,
            strides: IntArray,
            planes: Array<ByteBuffer?>
        ): IntArray {
            val planeWidths = intArrayOf(width, width / 2, width / 2)
            val planeHeights = intArrayOf(height, height / 2, height / 2)
            // Make a first pass to see if we need a temporary copy buffer.
            var copyCapacityNeeded = 0
            for (i in 0..2) {
                if (strides[i] > planeWidths[i]) {
                    copyCapacityNeeded = Math.max(
                        copyCapacityNeeded,
                        planeWidths[i] * planeHeights[i]
                    )
                }
            }
            // Allocate copy buffer if necessary.
            if (copyCapacityNeeded > 0
                && (copyBuffer == null || copyBuffer!!.capacity() < copyCapacityNeeded)
            ) {
                copyBuffer = ByteBuffer.allocateDirect(copyCapacityNeeded)
            }
            // Make sure YUV textures are allocated.
            if (yuvTextures == null) {
                yuvTextures = IntArray(3)
                for (i in 0..2) {
                    yuvTextures!![i] =
                        generateTexture(
                            GLES20.GL_TEXTURE_2D
                        )
                }
            }
            // Upload each plane.
            for (i in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures!![i])
                // GLES only accepts packed data, i.e. stride == planeWidth.
                val packedByteBuffer: ByteBuffer?
                packedByteBuffer = if (strides[i] == planeWidths[i]) {
                    // Input is packed already.
                    planes[i]
                } else {
                    YuvHelper.copyPlane(
                        planes[i],
                        strides[i],
                        copyBuffer,
                        planeWidths[i],
                        planeWidths[i],
                        planeHeights[i]
                    )
                    copyBuffer
                }
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    planeWidths[i],
                    planeHeights[i],
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    packedByteBuffer
                )
            }
            return yuvTextures!!
        }

        fun uploadFromBuffer(buffer: VideoFrameI420Buffer): IntArray {
            val strides =
                intArrayOf(buffer.strideY, buffer.strideU, buffer.strideV)
            val planes = arrayOf<ByteBuffer?>(
                buffer.dataY,
                buffer.dataU,
                buffer.dataV
            )
            return uploadYuvData(buffer.width, buffer.height, strides, planes)
        }

        /**
         * Releases cached resources. Uploader can still be used and the resources will be reallocated
         * on first use.
         */
        fun release() {
            copyBuffer = null
            if (yuvTextures != null) {
                GLES20.glDeleteTextures(3, yuvTextures, 0)
                yuvTextures = null
            }
        }
    }

    private val dstPoints = FloatArray(6)
    private val renderSize = Point()
    private var renderWidth = 0
    private var renderHeight = 0

    // Calculate the frame size after |renderMatrix| is applied. Stores the output in member variables
    // |renderWidth| and |renderHeight| to avoid allocations since this function is called for every
    // frame.
    private fun calculateTransformedRenderSize(
        frameWidth: Int, frameHeight: Int, renderMatrix: Matrix?
    ) {
        if (renderMatrix == null) {
            renderWidth = frameWidth
            renderHeight = frameHeight
            return
        }
        // Transform the texture coordinates (in the range [0, 1]) according to |renderMatrix|.
        renderMatrix.mapPoints(dstPoints, srcPoints)

        // Multiply with the width and height to get the positions in terms of pixels.
        for (i in 0..2) {
            dstPoints[i * 2 + 0] = dstPoints[i * 2 + 0] * frameWidth
            dstPoints[i * 2 + 1] = dstPoints[i * 2 + 1] * frameHeight
        }

        // Get the length of the sides of the transformed rectangle in terms of pixels.
        renderWidth = distance(
            dstPoints[0],
            dstPoints[1],
            dstPoints[2],
            dstPoints[3]
        )
        renderHeight = distance(
            dstPoints[0],
            dstPoints[1],
            dstPoints[4],
            dstPoints[5]
        )
    }

    private val yuvUploader = YuvUploader()

    // This variable will only be used for checking reference equality and is used for caching I420
    // textures.
    private var lastI420Frame: VideoFrame? = null
    private val renderMatrix = Matrix()
    fun drawFrame(
        frame: VideoFrame,
        drawer: GlDrawer
    ) {
        drawFrame(frame, drawer, null /* additionalRenderMatrix */)
    }

    private fun drawFrame(
        frame: VideoFrame,
        drawer: GlDrawer,
        additionalRenderMatrix: Matrix?
    ) {
        drawFrame(
            frame, drawer, additionalRenderMatrix, 0 /* viewportX */, 0 /* viewportY */,
            frame.getRotatedWidth(), frame.getRotatedHeight()
        )
    }

    fun drawFrame(
        frame: VideoFrame,
        drawer: GlDrawer,
        additionalRenderMatrix: Matrix?,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val width: Int = frame.getRotatedWidth()
        val height: Int = frame.getRotatedHeight()
        calculateTransformedRenderSize(width, height, additionalRenderMatrix)
        if (renderWidth <= 0 || renderHeight <= 0) {
            return
        }
        val isTextureFrame = frame.buffer is VideoFrameTextureBuffer
        renderMatrix.reset()
        renderMatrix.preTranslate(0.5f, 0.5f)
        if (!isTextureFrame) {
            renderMatrix.preScale(1f, -1f) // I420-frames are upside down
        }
        renderMatrix.preRotate(frame.rotation.toFloat())
        renderMatrix.preTranslate(-0.5f, -0.5f)
        if (additionalRenderMatrix != null) {
            renderMatrix.preConcat(additionalRenderMatrix)
        }
        // TODO FIX ME
        if (isTextureFrame) {
            Log.d(TAG, "texture frame")
            lastI420Frame = null
            drawTexture(
                drawer, frame.buffer as VideoFrameTextureBuffer, renderMatrix, renderWidth,
                renderHeight, viewportX, viewportY, viewportWidth, viewportHeight
            )
        } else {
            // Only upload the I420 data to textures once per frame, if we are called multiple times
            // with the same frame.
            if (frame !== lastI420Frame) {
                lastI420Frame = frame
                val i420Buffer: VideoFrameI420Buffer? = frame.buffer.toI420()
                if (i420Buffer != null) {
                    yuvUploader.uploadFromBuffer(i420Buffer)
                    i420Buffer.release()
                }
            }
            drawer.drawYuv(
                yuvUploader.yuvTextures,
                GlUtil.convertMatrixFromAndroidGraphicsMatrix(
                    renderMatrix
                ), renderWidth,
                renderHeight, viewportX, viewportY, viewportWidth, viewportHeight
            )
        }
    }

    fun prepareBufferForViewportSize(
        buffer: VideoFrameBuffer, width: Int, height: Int
    ): VideoFrameBuffer {
        buffer.retain()
        return buffer
    }

    fun release() {
        yuvUploader.release()
        lastI420Frame = null
    }

    companion object {
        const val TAG = "VideoFrameDrawer"

        /**
         * Draws a VideoFrame.TextureBuffer. Calls either drawer.drawOes or drawer.drawRgb
         * depending on the type of the buffer. You can supply an additional render matrix. This is
         * used multiplied together with the transformation matrix of the frame. (M = renderMatrix *
         * transformationMatrix)
         */
        fun drawTexture(
            drawer: GlDrawer,
            buffer: VideoFrameTextureBuffer,
            renderMatrix: Matrix?,
            frameWidth: Int,
            frameHeight: Int,
            viewportX: Int,
            viewportY: Int,
            viewportWidth: Int,
            viewportHeight: Int
        ) {
            val finalMatrix =
                Matrix(buffer.transformMatrix)
            finalMatrix.preConcat(renderMatrix)
            val finalGlMatrix =
                GlUtil.convertMatrixFromAndroidGraphicsMatrix(
                    finalMatrix
                )
            when (buffer.type) {
                VideoFrameTextureBuffer.Type.OES -> drawer.drawOes(
                    buffer.textureId, finalGlMatrix, frameWidth, frameHeight, viewportX,
                    viewportY, viewportWidth, viewportHeight
                )
                VideoFrameTextureBuffer.Type.RGB -> drawer.drawRgb(
                    buffer.textureId, finalGlMatrix, frameWidth, frameHeight, viewportX,
                    viewportY, viewportWidth, viewportHeight
                )
            }
        }

        private fun distance(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float
        ): Int {
            return Math.round(
                Math.hypot(
                    x1 - x0.toDouble(),
                    y1 - y0.toDouble()
                )
            ).toInt()
        }

        // These points are used to calculate the size of the part of the frame we are rendering.
        val srcPoints = floatArrayOf(
            0f /* x0 */,
            0f /* y0 */,
            1f /* x1 */,
            0f /* y1 */,
            0f /* x2 */,
            1f /* y2 */
        )
    }
}
