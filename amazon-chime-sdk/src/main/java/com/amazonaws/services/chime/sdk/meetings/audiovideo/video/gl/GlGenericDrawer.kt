package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.FloatBuffer


/**
 * Helper class to implement an instance of RendererCommon.GlDrawer that can accept multiple input
 * sources (OES, RGB, or YUV) using a generic fragment shader as input. The generic fragment shader
 * should sample pixel values from the function "sample" that will be provided by this class and
 * provides an abstraction for the input source type (OES, RGB, or YUV). The texture coordinate
 * variable name will be "tc" and the texture matrix in the vertex shader will be "tex_mat". The
 * simplest possible generic shader that just draws pixel from the frame unmodified looks like:
 * void main() {
 * gl_FragColor = sample(tc);
 * }
 * This class covers the cases for most simple shaders and generates the necessary boiler plate.
 * Advanced shaders can always implement RendererCommon.GlDrawer directly.
 */
open class GlGenericDrawer(
    private val vertexShader: String,
    private val genericFragmentSource: String,
    private val shaderCallbacks: ShaderCallbacks
) : GlDrawer {
    /**
     * The different shader types representing different input sources. YUV here represents three
     * separate Y, U, V textures.
     */
    enum class ShaderType {
        OES, RGB, YUV
    }

    /**
     * The shader callbacks is used to customize behavior for a GlDrawer. It provides a hook to set
     * uniform variables in the shader before a frame is drawn.
     */
    interface ShaderCallbacks {
        /**
         * This callback is called when a new shader has been compiled and created. It will be called
         * for the first frame as well as when the shader type is changed. This callback can be used to
         * do custom initialization of the shader that only needs to happen once.
         */
        fun onNewShader(shader: GlShader?)

        /**
         * This callback is called before rendering a frame. It can be used to do custom preparation of
         * the shader that needs to happen every frame.
         */
        fun onPrepareShader(
            shader: GlShader?,
            texMatrix: FloatArray?,
            frameWidth: Int,
            frameHeight: Int,
            viewportWidth: Int,
            viewportHeight: Int
        )
    }

    private var currentShaderType: ShaderType? = null

    private var currentShader: GlShader? =
        null
    private var inPosLocation = 0
    private var inTcLocation = 0
    private var texMatrixLocation = 0

    constructor(
        genericFragmentSource: String,
        shaderCallbacks: ShaderCallbacks
    ) : this(
        DEFAULT_VERTEX_SHADER_STRING,
        genericFragmentSource,
        shaderCallbacks
    ) {
    }

    // Visible for testing.
    fun createShader(shaderType: ShaderType): GlShader {
        return GlShader(
            vertexShader,
            createFragmentShaderString(
                genericFragmentSource,
                shaderType
            )
        )
    }

    /**
     * Draw an OES texture frame with specified texture transformation matrix. Required resources are
     * allocated at the first call to this function.
     */
    override fun drawOes(
        oesTextureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
        viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
    ) {
        prepareShader(
            ShaderType.OES, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
        )
        // Bind the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GlUtil.checkGlError("glBindTexture")

        // Draw the texture.
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError("glDrawArrays")

        // Unbind the texture as a precaution.
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Draw a RGB(A) texture frame with specified texture transformation matrix. Required resources
     * are allocated at the first call to this function.
     */
    override fun drawRgb(
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
    override fun drawYuv(
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
            if (currentShader != null) {
                currentShader!!.release()
            }
            shader = createShader(shaderType)
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

            shaderCallbacks.onNewShader(shader)
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

        // Do custom per-frame shader preparation.
        shaderCallbacks.onPrepareShader(
            shader, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
        )
    }

    /**
     * Release all GLES resources. This needs to be done manually, otherwise the resources are leaked.
     */
    override fun release() {
        if (currentShader != null) {
            currentShader!!.release()
            currentShader = null
            currentShaderType = null
        }
    }

    companion object {
        private const val INPUT_VERTEX_COORDINATE_NAME = "in_pos"
        private const val INPUT_TEXTURE_COORDINATE_NAME = "in_tc"
        private const val TEXTURE_MATRIX_NAME = "tex_mat"
        private const val DEFAULT_VERTEX_SHADER_STRING = ("varying vec2 tc;\n"
                + "attribute vec4 in_pos;\n"
                + "attribute vec4 in_tc;\n"
                + "uniform mat4 tex_mat;\n"
                + "void main() {\n"
                + "  gl_Position = in_pos;\n"
                + "  tc = (tex_mat * in_tc).xy;\n"
                + "}\n")

        // Vertex coordinates in Normalized Device Coordinates, i.e. (-1, -1) is bottom-left and (1, 1)
        // is top-right.
        private val FULL_RECTANGLE_BUFFER: FloatBuffer =
            GlUtil.createFloatBuffer(
                floatArrayOf(
                    -1.0f, -1.0f,  // Bottom left.
                    1.0f, -1.0f,  // Bottom right.
                    -1.0f, 1.0f,  // Top left.
                    1.0f, 1.0f
                )
            )

        // Texture coordinates - (0, 0) is bottom-left and (1, 1) is top-right.
        private val FULL_RECTANGLE_TEXTURE_BUFFER: FloatBuffer =
            GlUtil.createFloatBuffer(
                floatArrayOf(
                    0.0f, 0.0f,  // Bottom left.
                    1.0f, 0.0f,  // Bottom right.
                    0.0f, 1.0f,  // Top left.
                    1.0f, 1.0f
                )
            )

        fun createFragmentShaderString(
            genericFragmentSource: String,
            shaderType: ShaderType
        ): String {
            val stringBuilder = StringBuilder()
            if (shaderType == ShaderType.OES) {
                stringBuilder.append("#extension GL_OES_EGL_image_external : require\n")
            }
            stringBuilder.append("precision mediump float;\n")
            stringBuilder.append("varying vec2 tc;\n")
            if (shaderType == ShaderType.YUV) {
                stringBuilder.append("uniform sampler2D y_tex;\n")
                stringBuilder.append("uniform sampler2D u_tex;\n")
                stringBuilder.append("uniform sampler2D v_tex;\n")

                // Add separate function for sampling texture.
                // yuv_to_rgb_mat is inverse of the matrix defined in YuvConverter.
                stringBuilder.append("vec4 sample(vec2 p) {\n")
                stringBuilder.append("  float y = texture2D(y_tex, p).r * 1.16438;\n")
                stringBuilder.append("  float u = texture2D(u_tex, p).r;\n")
                stringBuilder.append("  float v = texture2D(v_tex, p).r;\n")
                stringBuilder.append("  return vec4(y + 1.59603 * v - 0.874202,\n")
                stringBuilder.append("    y - 0.391762 * u - 0.812968 * v + 0.531668,\n")
                stringBuilder.append("    y + 2.01723 * u - 1.08563, 1);\n")
                stringBuilder.append("}\n")
                stringBuilder.append(genericFragmentSource)
            } else {
                val samplerName =
                    if (shaderType == ShaderType.OES) "samplerExternalOES" else "sampler2D"
                stringBuilder.append("uniform ").append(samplerName).append(" tex;\n")

                // Update the sampling function in-place.
                stringBuilder.append(genericFragmentSource.replace("sample(", "texture2D(tex, "))
            }
            return stringBuilder.toString()
        }
    }

}
