package com.amazonaws.services.chime.sdk.meetings.internal.video

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import java.nio.ByteBuffer

class VideoFrameI420BufferAdapter(
    private val i420Buffer: VideoFrameI420Buffer
) : com.xodee.client.video.VideoFrame.I420Buffer {
    override fun getWidth(): Int {
        return i420Buffer.getWidth()
    }

    override fun getHeight(): Int {
        return i420Buffer.getHeight()
    }

    override fun getDataY(): ByteBuffer? {
        return i420Buffer.dataY()
    }

    override fun getDataU(): ByteBuffer? {
        return i420Buffer.dataU()
    }

    override fun getDataV(): ByteBuffer? {
        return i420Buffer.dataV()
    }

    override fun getStrideY(): Int {
        return i420Buffer.strideY()
    }

    override fun getStrideU(): Int {
        return i420Buffer.strideU()
    }

    override fun getStrideV(): Int {
        return i420Buffer.strideV()
    }


    override fun toI420(): com.xodee.client.video.VideoFrame.I420Buffer? {
        return this
    }

    override fun cropAndScale(
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        scaleWidth: Int,
        scaleHeight: Int
    ): com.xodee.client.video.VideoFrame.Buffer? {
        return VideoFrameI420BufferAdapter(
            i420Buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight) as VideoFrameI420Buffer)
    }

    override fun retain() {
        i420Buffer.retain()
    }

    override fun release() {
        i420Buffer.release()
    }
}