package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.graphics.Point
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31Ext
import android.util.Log
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer
import com.xodee.client.video.YuvUtil
import java.nio.ByteBuffer
import java.nio.FloatBuffer

private const val VERTEX_SHADER =
"""
varying vec2 tc;
attribute vec4 in_pos;
attribute vec4 in_tc;
uniform mat4 tex_mat;
void main() {
    gl_Position = in_pos;
    tc = (tex_mat * in_tc).xy;
}
"""

private val FRAGMENT_SHADER_OES: String =
"""
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 tc;
uniform samplerExternalOES tex;

void main() {
  gl_FragColor = texture2D(tex, tc);
}
"""

private val FRAGMENT_SHADER_RGB: String =
"""
precision mediump float;
varying vec2 tc;
uniform samplerExternalOES tex;

void main() {
  gl_FragColor = texture2D(tex, tc);
}
"""

private val FRAGMENT_SHADER_YUV: String =
"""
precision mediump float;

varying vec2 tc;

uniform sampler2D y_tex;
uniform sampler2D u_tex;
uniform sampler2D v_tex;

vec4 sample(vec2 p) {
  float y = texture2D(y_tex, p).r * 1.16438;
  float u = texture2D(u_tex, p).r;
  float v = texture2D(v_tex, p).r;
  return vec4(y + 1.59603 * v - 0.874202,
    y - 0.391762 * u - 0.812968 * v + 0.531668,
    y + 2.01723 * u - 1.08563, 1);
}

void main() {
  gl_FragColor = sample(tc);
}
"""

// Vertex coordinates in Normalized Device Coordinates, i.e. (-1, -1) is bottom-left and (1, 1)
// is top-right.
private val FULL_RECTANGLE_BUFFER: FloatBuffer =
    DefaultEglCore.createFloatBuffer(
        floatArrayOf(
            -1.0f, -1.0f,  // Bottom left.
            1.0f, -1.0f,  // Bottom right.
            -1.0f, 1.0f,  // Top left.
            1.0f, 1.0f
        )
    )

// Texture coordinates - (0, 0) is bottom-left and (1, 1) is top-right.
private val FULL_RECTANGLE_TEXTURE_BUFFER: FloatBuffer =
    DefaultEglCore.createFloatBuffer(
        floatArrayOf(
            0.0f, 0.0f,  // Bottom left.
            1.0f, 0.0f,  // Bottom right.
            0.0f, 1.0f,  // Top left.
            1.0f, 1.0f
        )
    )

/**
 * [DefaultGlVideoFrameDrawer] simply draws the frames as opaque quads onto the current surface
 */
class DefaultGlVideoFrameDrawer : GlVideoFrameDrawer {
    enum class ShaderType {
        OES, RGB, YUV
    }

    private var currentShaderType: ShaderType? = null

    private var currentShader: GlShader? =
        null
    private var inPosLocation = 0
    private var inTcLocation = 0
    private var texMatrixLocation = 0

    override fun drawVideoFrame(frame: VideoFrame) {
        TODO("Not yet implemented")
    }

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
                Log.e("test", "allocating")
                copyBuffer = ByteBuffer.allocateDirect(copyCapacityNeeded)
            }
            // Make sure YUV textures are allocated.
            if (yuvTextures == null) {
                yuvTextures = IntArray(3)
                for (i in 0..2) {
                    yuvTextures!![i] =
                        DefaultEglCore.generateTexture(
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
                    YuvUtil.copyPlane(
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

    private val renderMatrix = Matrix()

    fun drawFrame(
        frame: VideoFrame,
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
        if (isTextureFrame) {
            Log.e("ASD", "TEXTUre")
            val textureBuffer = frame.buffer as VideoFrameTextureBuffer
            val finalMatrix =
                Matrix(textureBuffer.transformMatrix)
            finalMatrix.preConcat(renderMatrix)
            val finalGlMatrix =
                DefaultEglCore.convertMatrixFromAndroidGraphicsMatrix(
                    finalMatrix
                )
            when (textureBuffer.type) {
                VideoFrameTextureBuffer.Type.OES -> drawOes(
                    textureBuffer.textureId, finalGlMatrix, textureBuffer.width, textureBuffer.height, viewportX,
                    viewportY, viewportWidth, viewportHeight
                )
                VideoFrameTextureBuffer.Type.RGB -> drawRgb(
                    textureBuffer.textureId, finalGlMatrix, textureBuffer.width, textureBuffer.height, viewportX,
                    viewportY, viewportWidth, viewportHeight
                )
            }
        } else if (frame.buffer is VideoFrameI420Buffer) {
            yuvUploader.uploadFromBuffer(frame.buffer)
            drawYuv(
                yuvUploader.yuvTextures,
                DefaultEglCore.convertMatrixFromAndroidGraphicsMatrix(
                    renderMatrix
                ), renderWidth,
                renderHeight, viewportX, viewportY, viewportWidth, viewportHeight
            )
        }
    }

    /**
     * Draw an OES texture frame with specified texture transformation matrix. Required resources are
     * allocated at the first call to this function.
     */
    fun drawOes(
        oesTextureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
        viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
    ) {
        prepareShader(
            ShaderType.OES, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
        )
        // Bind the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        DefaultEglCore.checkGlError("glBindTexture")

        // Draw the texture.
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        DefaultEglCore.checkGlError("glDrawArrays")

        // Unbind the texture as a precaution.
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Draw a RGB(A) texture frame with specified texture transformation matrix. Required resources
     * are allocated at the first call to this function.
     */
    fun drawRgb(
        textureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
        viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
    ) {
        prepareShader(
            ShaderType.RGB, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
        )
        // Bind the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // Draw the texture.
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Unbind the texture as a precaution.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Draw a YUV frame with specified texture transformation matrix. Required resources are allocated
     * at the first call to this function.
     */
    fun drawYuv(
        yuvTextures: IntArray?, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
        viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
    ) {
        prepareShader(
            ShaderType.YUV, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
        )
        // Bind the textures.
        for (i in 0..2) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures!![i])
        }
        // Draw the textures.
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Unbind the textures as a precaution.
        for (i in 0..2) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    private fun prepareShader(
        shaderType: ShaderType, texMatrix: FloatArray?, frameWidth: Int,
        frameHeight: Int, viewportWidth: Int, viewportHeight: Int
    ) {
        val shader: GlShader?
        if (shaderType == currentShaderType) {
            // Same shader type as before, reuse exising shader.
            shader = currentShader
        } else {
            // Allocate new shader.
            currentShaderType = shaderType
            currentShader?.release()
            shader = GlShader(
                VERTEX_SHADER,
                FRAGMENT_SHADER_YUV
            )
            currentShader = shader
            shader.useProgram()

            // Set input texture units.
            if (shaderType == ShaderType.YUV) {
                GLES20.glUniform1i(shader.getUniformLocation("y_tex"), 0)
                GLES20.glUniform1i(shader.getUniformLocation("u_tex"), 1)
                GLES20.glUniform1i(shader.getUniformLocation("v_tex"), 2)
            } else {
                GLES20.glUniform1i(shader.getUniformLocation("tex"), 0)
            }

            texMatrixLocation =
                shader.getUniformLocation(TEXTURE_MATRIX_NAME)
            inPosLocation =
                shader.getAttribLocation(INPUT_VERTEX_COORDINATE_NAME)
            inTcLocation =
                shader.getAttribLocation(INPUT_TEXTURE_COORDINATE_NAME)
        }
        shader!!.useProgram()

        // Upload the vertex coordinates.
        GLES20.glEnableVertexAttribArray(inPosLocation)
        GLES20.glVertexAttribPointer(
            inPosLocation,  /* size= */2,  /* type= */
            GLES20.GL_FLOAT,  /* normalized= */false,  /* stride= */0,
            FULL_RECTANGLE_BUFFER
        )

        // Upload the texture coordinates.
        GLES20.glEnableVertexAttribArray(inTcLocation)
        GLES20.glVertexAttribPointer(
            inTcLocation,  /* size= */2,  /* type= */
            GLES20.GL_FLOAT,  /* normalized= */false,  /* stride= */0,
            FULL_RECTANGLE_TEXTURE_BUFFER
        )

        // Upload the texture transformation matrix.
        GLES20.glUniformMatrix4fv(
            texMatrixLocation, 1 /* count= */, false /* transpose= */, texMatrix, 0 /* offset= */
        )
    }

    fun release() {
        yuvUploader.release()

        if (currentShader != null) {
            currentShader!!.release()
            currentShader = null
            currentShaderType = null
        }
    }

    companion object {
        const val TAG = "VideoFrameDrawer"

            private const val INPUT_VERTEX_COORDINATE_NAME = "in_pos"
            private const val INPUT_TEXTURE_COORDINATE_NAME = "in_tc"
            private const val TEXTURE_MATRIX_NAME = "tex_mat"

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