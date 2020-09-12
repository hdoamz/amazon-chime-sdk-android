package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import java.nio.ByteBuffer

class DefaultVideoFrameI420Buffer(
    private val width: Int,
    private val height: Int,
    private val dataY: ByteBuffer?,
    private val dataU: ByteBuffer?,
    private val dataV: ByteBuffer?,
    private val strideY: Int,
    private val strideU: Int,
    private val strideV: Int
) : VideoFrameI420Buffer {
    override fun getWidth(): Int {
        return width
    }

    override fun getHeight(): Int {
        return height
    }

    override fun dataY(): ByteBuffer? {
        return dataY
    }

    override fun dataU(): ByteBuffer? {
        return dataU
    }

    override fun dataV(): ByteBuffer? {
        return dataV
    }

    override fun strideY(): Int {
        return strideY
    }

    override fun strideU(): Int {
        return strideU
    }

    override fun strideV(): Int {
        return strideV
    }

    override fun toI420(): VideoFrameI420Buffer? {
        return this
    }

    override fun cropAndScale(
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        scaleWidth: Int,
        scaleHeight: Int
    ): VideoFrameBuffer? {
        return this
    }

    override fun retain() {
        return
    }

    override fun release() {
        return
    }
}