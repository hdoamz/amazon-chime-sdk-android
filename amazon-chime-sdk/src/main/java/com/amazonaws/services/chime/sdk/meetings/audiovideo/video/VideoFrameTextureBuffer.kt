package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.graphics.Matrix
import android.opengl.GLES11Ext
import android.opengl.GLES20

interface VideoFrameTextureBuffer : VideoFrameBuffer {
    enum class Type(val glTarget: Int) {
        OES(GLES11Ext.GL_TEXTURE_EXTERNAL_OES),
        RGB(GLES20.GL_TEXTURE_2D);
    }

    fun textureId(): Int
    fun transformMatrix(): Matrix?
    fun type(): Type
}