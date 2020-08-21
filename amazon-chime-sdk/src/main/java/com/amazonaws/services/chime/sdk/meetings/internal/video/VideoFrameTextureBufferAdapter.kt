package com.amazonaws.services.chime.sdk.meetings.internal.video

import android.graphics.Matrix
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame

class VideoFrameTextureBufferAdapter(
    private val textureBuffer: VideoFrame.TextureBuffer
) : com.xodee.client.video.VideoFrame.TextureBuffer {

    override fun getWidth(): Int {
        return textureBuffer.getWidth()
    }

    override fun getHeight(): Int {
        return textureBuffer.getHeight()
    }

    override fun getTransformMatrix(): Matrix? {
        return textureBuffer.transformMatrix()
    }

    override fun getTextureId(): Int {
        return textureBuffer.textureId();
    }

    override fun toI420(): com.xodee.client.video.VideoFrame.I420Buffer? {
        return textureBuffer.toI420()?.let { VideoFrameI420BufferAdapter(it) }
    }

    override fun cropAndScale(
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        scaleWidth: Int,
        scaleHeight: Int
    ): com.xodee.client.video.VideoFrame.Buffer? {
        return VideoFrameTextureBufferAdapter(
            textureBuffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight) as VideoFrame.TextureBuffer)
    }
}