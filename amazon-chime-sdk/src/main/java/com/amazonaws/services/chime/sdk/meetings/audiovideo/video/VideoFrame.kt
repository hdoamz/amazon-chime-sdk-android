package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.graphics.Matrix
import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer


class VideoFrame {
    interface Buffer {
        fun getWidth(): Int
        fun getHeight(): Int

        fun toI420(): I420Buffer?

        fun cropAndScale(
            cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int
        ): Buffer?
    }

    interface I420Buffer : Buffer {
        fun dataY(): ByteBuffer?
        fun dataU(): ByteBuffer?
        fun dataV(): ByteBuffer?

        fun strideY(): Int
        fun strideU(): Int
        fun strideV(): Int
    }

    interface TextureBuffer : Buffer {
        enum class Type(val glTarget: Int) {
            OES(GLES11Ext.GL_TEXTURE_EXTERNAL_OES), RGB(GLES20.GL_TEXTURE_2D);
        }

        fun textureId(): Int
        fun transformMatrix(): Matrix?
        fun type(): Type
    }

    /**
     * State of video tile
     */
    var width: Int = 0

    /**
     * View which will be used to render the Video Frame
     */
    var height: Int = 0

    /**
     * State of video tile
     */
    var timestamp: Long = 0

    /**
     * View which will be used to render the Video Frame
     */
    var buffer: Buffer? = null

    /**
     * View which will be used to render the Video Frame
     */
    var rotation: Int = 0

    fun getRotatedWidth(): Int {
        return width
    }

    fun getRotatedHeight(): Int {
        return height
    }

    constructor(width: Int, height: Int, timestamp: Long, buffer: Buffer? = null) {
        this.width = width
        this.height = height
        this.timestamp = timestamp
        this.buffer = buffer
    }
}