package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import java.nio.ByteBuffer

class DefaultVideoFrameI420Buffer(
    override val width: Int,
    override val height: Int,
    override val dataY: ByteBuffer?,
    override val dataU: ByteBuffer?,
    override val dataV: ByteBuffer?,
    override val strideY: Int,
    override val strideU: Int,
    override val strideV: Int
) : VideoFrameI420Buffer {
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