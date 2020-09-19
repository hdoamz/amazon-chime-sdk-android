package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.graphics.Matrix
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Some OpenGL utility functions.
 */
object GlUtil {
    const val TAG = "GlUtil"

    private const val SIZEOF_FLOAT = 4

    /**
     * Checks to see if a GLES error has been raised.
     */
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }

    /**
     * Allocates a direct float buffer, and populates it with the float array data.
     */
    fun createFloatBuffer(coords: FloatArray): FloatBuffer {
        // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
        val bb =
            ByteBuffer.allocateDirect(coords.size * SIZEOF_FLOAT)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(coords)
        fb.position(0)
        return fb
    }


    /**
     * Generate texture with standard parameters.
     */
    fun generateTexture(target: Int): Int {
        val textureArray = IntArray(1)
        GLES20.glGenTextures(1, textureArray, 0)
        val textureId = textureArray[0]
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        checkGlError("generateTexture")
        return textureId
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
        return floatArrayOf(
            values[0 * 3 + 0], values[1 * 3 + 0], 0f, values[2 * 3 + 0],
            values[0 * 3 + 1], values[1 * 3 + 1], 0f, values[2 * 3 + 1], 0f, 0f, 1f, 0f,
            values[0 * 3 + 2], values[1 * 3 + 2], 0f, values[2 * 3 + 2]
        )
    }
}