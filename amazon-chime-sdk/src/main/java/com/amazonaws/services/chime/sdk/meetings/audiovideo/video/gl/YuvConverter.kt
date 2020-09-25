package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.opengl.GLES20
import com.amazon.chime.webrtc.GlTextureFrameBuffer
import com.amazon.chime.webrtc.GlUtil
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.DefaultVideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer
import com.xodee.client.video.JniUtil
import kotlinx.coroutines.Runnable
import java.nio.ByteBuffer

/**
 * Class for converting OES textures to a YUV ByteBuffer. It can be constructed on any thread, but
 * should only be operated from a single thread with an active EGL context.
 */
class YuvConverter @JvmOverloads constructor(private val videoFrameDrawer: VideoFrameDrawer = VideoFrameDrawer()) {
    private class ShaderCallbacks :
        GlGenericDrawer.ShaderCallbacks {
        private var xUnitLoc = 0
        private var coeffsLoc = 0
        private var coeffs: FloatArray? = null
        private var stepSize = 0f
        fun setPlaneY() {
            coeffs = yCoeffs
            stepSize = 1.0f
        }

        fun setPlaneU() {
            coeffs = uCoeffs
            stepSize = 2.0f
        }

        fun setPlaneV() {
            coeffs = vCoeffs
            stepSize = 2.0f
        }

        override fun onNewShader(shader: GlShader?) {
            xUnitLoc = shader!!.getUniformLocation("xUnit")
            coeffsLoc = shader.getUniformLocation("coeffs")
        }

        override fun onPrepareShader(
            shader: GlShader?,
            texMatrix: FloatArray?,
            frameWidth: Int,
            frameHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int
        ) {
            GLES20.glUniform4fv(coeffsLoc,  /* count= */1, coeffs,  /* offset= */0)
            // Matrix * (1;0;0;0) / (width / stepSize). Note that OpenGL uses column major order.
            GLES20.glUniform2f(
                xUnitLoc,
                stepSize * texMatrix!![0] / frameWidth,
                stepSize * texMatrix[1] / frameWidth
            )
        }

        companion object {
            // Y'UV444 to RGB888, see https://en.wikipedia.org/wiki/YUV#Y%E2%80%B2UV444_to_RGB888_conversion
            // We use the ITU-R BT.601 coefficients for Y, U and V.
            // The values in Wikipedia are inaccurate, the accurate values derived from the spec are:
            // Y = 0.299 * R + 0.587 * G + 0.114 * B
            // U = -0.168736 * R - 0.331264 * G + 0.5 * B + 0.5
            // V = 0.5 * R - 0.418688 * G - 0.0813124 * B + 0.5
            // To map the Y-values to range [16-235] and U- and V-values to range [16-240], the matrix has
            // been multiplied with matrix:
            // {{219 / 255, 0, 0, 16 / 255},
            // {0, 224 / 255, 0, 16 / 255},
            // {0, 0, 224 / 255, 16 / 255},
            // {0, 0, 0, 1}}
            private val yCoeffs =
                floatArrayOf(0.256788f, 0.504129f, 0.0979059f, 0.0627451f)
            private val uCoeffs =
                floatArrayOf(-0.148223f, -0.290993f, 0.439216f, 0.501961f)
            private val vCoeffs =
                floatArrayOf(0.439216f, -0.367788f, -0.0714274f, 0.501961f)
        }
    }

    private val i420TextureFrameBuffer: GlTextureFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)
    private val shaderCallbacks = ShaderCallbacks()
    private val drawer =
        GlGenericDrawer(
            FRAGMENT_SHADER,
            shaderCallbacks
        )

    /** Converts the texture buffer to I420.  */
    fun convert(inputTextureBuffer: VideoFrameTextureBuffer): VideoFrameI420Buffer {

        val frameWidth: Int = inputTextureBuffer.width
        val frameHeight: Int = inputTextureBuffer.height
        val stride = (frameWidth + 7) / 8 * 8
        val uvHeight = (frameHeight + 1) / 2
        // Total height of the combined memory layout.
        val totalHeight = frameHeight + uvHeight
        val i420ByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(stride * totalHeight)
        // Viewport width is divided by four since we are squeezing in four color bytes in each RGBA
        // pixel.
        val viewportWidth = stride / 4

        // Produce a frame buffer starting at top-left corner, not bottom-left.
        val renderMatrix = Matrix()
        renderMatrix.preTranslate(0.5f, 0.5f)
        renderMatrix.preScale(1f, -1f)
        renderMatrix.preTranslate(-0.5f, -0.5f)

        i420TextureFrameBuffer.setSize(viewportWidth, totalHeight)

        // Bind our framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, i420TextureFrameBuffer.frameBufferId)
        EglCore.checkGlError("glBindFramebuffer")

        // Draw Y.
        shaderCallbacks.setPlaneY()
        drawTexture(inputTextureBuffer,
            drawer, renderMatrix, frameWidth, frameHeight,  /* viewportX= */
            0,  /* viewportY= */0, viewportWidth,  /* viewportHeight= */
            frameHeight
        )

        // Draw U.
        shaderCallbacks.setPlaneU()
        drawTexture(inputTextureBuffer,
            drawer, renderMatrix, frameWidth, frameHeight,  /* viewportX= */
            0,  /* viewportY= */frameHeight, viewportWidth / 2,  /* viewportHeight= */
            uvHeight
        )

        // Draw V.
        shaderCallbacks.setPlaneV()
        drawTexture(inputTextureBuffer,
            drawer,
            renderMatrix,
            frameWidth,
            frameHeight,  /* viewportX= */
            viewportWidth / 2,  /* viewportY= */
            frameHeight,
            viewportWidth / 2,  /* viewportHeight= */
            uvHeight
        )

        GLES20.glReadPixels(
            0, 0, i420TextureFrameBuffer.width, i420TextureFrameBuffer.height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, i420ByteBuffer
        )

        EglCore.checkGlError("YuvConverter.convert")

        // Restore normal framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // Prepare Y, U, and V ByteBuffer slices.
        val yPos = 0
        val uPos = yPos + stride * frameHeight
        // Rows of U and V alternate in the buffer, so V data starts after the first row of U.
        val vPos = uPos + stride / 2

        i420ByteBuffer.position(yPos)
        i420ByteBuffer.limit(yPos + stride * frameHeight)
        val dataY: ByteBuffer = i420ByteBuffer.slice()

        i420ByteBuffer.position(uPos)
        // The last row does not have padding.
        val uvSize = stride * (uvHeight - 1) + stride / 2
        i420ByteBuffer.limit(uPos + uvSize)
        val dataU: ByteBuffer = i420ByteBuffer.slice()

        i420ByteBuffer.position(vPos)
        i420ByteBuffer.limit(vPos + uvSize)
        val dataV: ByteBuffer = i420ByteBuffer.slice()

        return DefaultVideoFrameI420Buffer(
            frameWidth,
            frameHeight,
            dataY,
            dataU,
            dataV,
            stride,
            stride,
            stride,
            Runnable { JniUtil.nativeFreeByteBuffer(i420ByteBuffer) }
        );
    }

    fun release() {
        drawer.release()
        i420TextureFrameBuffer.release()
        videoFrameDrawer.release()
        // Allow this class to be reused.
    }

    /**
     * Draws a VideoFrame.TextureBuffer. Calls either drawer.drawOes or drawer.drawRgb
     * depending on the type of the buffer. You can supply an additional render matrix. This is
     * used multiplied together with the transformation matrix of the frame. (M = renderMatrix *
     * transformationMatrix)
     */
    fun drawTexture(
        inputTextureBuffer: VideoFrameTextureBuffer,
        drawer: GlDrawer,
        renderMatrix: Matrix?,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val finalMatrix =
            Matrix(inputTextureBuffer.transformMatrix)
        finalMatrix.preConcat(renderMatrix)
        val finalGlMatrix: FloatArray =
            EglCore.convertMatrixFromAndroidGraphicsMatrix(
                finalMatrix
            )

        when (inputTextureBuffer.type) {
            VideoFrameTextureBuffer.Type.OES -> drawer.drawOes(
                inputTextureBuffer.textureId, finalGlMatrix, frameWidth, frameHeight, viewportX,
                viewportY, viewportWidth, viewportHeight
            )
            VideoFrameTextureBuffer.Type.RGB -> drawer.drawRgb(
                inputTextureBuffer.textureId, finalGlMatrix, frameWidth, frameHeight, viewportX,
                viewportY, viewportWidth, viewportHeight
            )
        }
    }

    companion object {
        private const val FRAGMENT_SHADER =
        // Difference in texture coordinate corresponding to one
            // sub-pixel in the x direction.
            ("uniform vec2 xUnit;\n" // Color conversion coefficients, including constant term
                    + "uniform vec4 coeffs;\n"
                    + "\n"
                    + "void main() {\n" // Since the alpha read from the texture is always 1, this could
                    // be written as a mat4 x vec4 multiply. However, that seems to
                    // give a worse framerate, possibly because the additional
                    // multiplies by 1.0 consume resources. TODO(nisse): Could also
                    // try to do it as a vec3 x mat3x4, followed by an add in of a
                    // constant vector.
                    + "  gl_FragColor.r = coeffs.a + dot(coeffs.rgb,\n"
                    + "      sample(tc - 1.5 * xUnit).rgb);\n"
                    + "  gl_FragColor.g = coeffs.a + dot(coeffs.rgb,\n"
                    + "      sample(tc - 0.5 * xUnit).rgb);\n"
                    + "  gl_FragColor.b = coeffs.a + dot(coeffs.rgb,\n"
                    + "      sample(tc + 0.5 * xUnit).rgb);\n"
                    + "  gl_FragColor.a = coeffs.a + dot(coeffs.rgb,\n"
                    + "      sample(tc + 1.5 * xUnit).rgb);\n"
                    + "}\n")
    }
}