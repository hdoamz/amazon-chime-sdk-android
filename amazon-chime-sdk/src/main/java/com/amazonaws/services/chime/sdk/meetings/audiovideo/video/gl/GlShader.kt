package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.opengl.GLES20
import java.nio.FloatBuffer


// Helper class for handling OpenGL shaders and shader programs.
class GlShader(vertexSource: String, fragmentSource: String) {
    private var program: Int
    fun getAttribLocation(label: String): Int {
        if (program == -1) {
            throw RuntimeException("The program has been released")
        }
        val location = GLES20.glGetAttribLocation(program, label)
        if (location < 0) {
            throw RuntimeException("Could not locate '$label' in program")
        }
        return location
    }

    /**
     * Enable and upload a vertex array for attribute |label|. The vertex data is specified in
     * |buffer| with |dimension| number of components per vertex.
     */
    fun setVertexAttribArray(
        label: String,
        dimension: Int,
        buffer: FloatBuffer?
    ) {
        setVertexAttribArray(label, dimension, 0 /* stride */, buffer)
    }

    /**
     * Enable and upload a vertex array for attribute |label|. The vertex data is specified in
     * |buffer| with |dimension| number of components per vertex and specified |stride|.
     */
    fun setVertexAttribArray(
        label: String,
        dimension: Int,
        stride: Int,
        buffer: FloatBuffer?
    ) {
        if (program == -1) {
            throw RuntimeException("The program has been released")
        }
        val location = getAttribLocation(label)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, dimension, GLES20.GL_FLOAT, false, stride, buffer)
        GlUtil.checkGlError("setVertexAttribArray")
    }

    fun getUniformLocation(label: String): Int {
        if (program == -1) {
            throw RuntimeException("The program has been released")
        }
        val location = GLES20.glGetUniformLocation(program, label)
        GlUtil.checkGlError("setVertexAttribArray")

        if (location < 0) {
            throw RuntimeException("Could not locate uniform '$label' in program")
        }
        return location
    }

    fun useProgram() {
        if (program == -1) {
            throw RuntimeException("The program has been released")
        }
        GLES20.glUseProgram(program)
        GlUtil.checkGlError("glUseProgram")
    }

    fun release() {
        // Delete program, automatically detaching any shaders from it.
        if (program != -1) {
            GLES20.glDeleteProgram(program)
            program = -1
        }
    }

    companion object {
        private const val TAG = "GlShader"
        private fun compileShader(shaderType: Int, source: String): Int {
            val shader = GLES20.glCreateShader(shaderType)
            if (shader == 0) {
                throw RuntimeException("glCreateShader() failed. GLES20 error: " + GLES20.glGetError())
            }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = intArrayOf(GLES20.GL_FALSE)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] != GLES20.GL_TRUE) {
                throw RuntimeException(GLES20.glGetShaderInfoLog(shader))
            }
            GlUtil.checkGlError("compileShader")
            return shader
        }
    }

    init {
        val vertexShader =
            compileShader(
                GLES20.GL_VERTEX_SHADER,
                vertexSource
            )
        val fragmentShader =
            compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                fragmentSource
            )
        program = GLES20.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("glCreateProgram() failed. GLES20 error: " + GLES20.glGetError())
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = intArrayOf(GLES20.GL_FALSE)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            throw RuntimeException(GLES20.glGetProgramInfoLog(program))
        }
        // According to the documentation of glLinkProgram():
        // "After the link operation, applications are free to modify attached shader objects, compile
        // attached shader objects, detach shader objects, delete shader objects, and attach additional
        // shader objects. None of these operations affects the information log or the program that is
        // part of the program object."
        // But in practice, detaching shaders from the program seems to break some devices. Deleting the
        // shaders are fine however - it will delete them when they are no longer attached to a program.
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        GlUtil.checkGlError("Creating GlShader")
    }
}
