package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.graphics.Matrix
import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * [VideoFrameTextureBuffer] is a buffer which maintains a GLES texture
 */
interface VideoFrameTextureBuffer : VideoFrameBuffer {
    /**
     * Wrapper enum of underlying supported GL texture types
     */
    enum class Type(val glTarget: Int) {
        OES(GLES11Ext.GL_TEXTURE_EXTERNAL_OES),
        RGB(GLES20.GL_TEXTURE_2D);
    }

    /**
     * ID of underlying GL texture
     */
    val textureId: Int

    /**
     * The transform matrix associated with the frame. This transform matrix maps 2D
     * homogeneous coordinates of the form (s, t, 1) with s and t in the inclusive range [0, 1] to
     * the coordinate that should be used to sample that location from the buffer.
     */
    val transformMatrix: Matrix?

    /**
     * GL type of underlying GL texture
     */
    val type: Type
}