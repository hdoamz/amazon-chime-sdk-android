package com.amazonaws.services.chime.sdk.meetings.internal.video

class VideoFrameBufferAdapter(
    private val buffer: com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame.Buffer
) : com.xodee.client.video.VideoFrame.Buffer {
    override fun getWidth(): Int {
        return buffer.getWidth()
    }

    override fun getHeight(): Int {
        return buffer.getHeight()
    }

    override fun toI420(): com.xodee.client.video.VideoFrame.I420Buffer? {
        return buffer.toI420()?.let { VideoFrameI420BufferAdapter(it) }
    }

    override fun cropAndScale(
        cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int
    ) : com.xodee.client.video.VideoFrame.Buffer? {
        return buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight)?.let {
            VideoFrameBufferAdapter(
                it
            )
        }
    }
}