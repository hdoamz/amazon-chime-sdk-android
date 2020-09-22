package com.amazonaws.services.chime.sdk.meetings.internal.video.adapters

import android.graphics.Matrix
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer
import java.security.InvalidParameterException

class VideoFrameTextureBufferAdapter{
    class SdkToVideoClient(
        private val textureBuffer: VideoFrameTextureBuffer
    ) : com.xodee.client.video.VideoFrameTextureBuffer {

        override fun getWidth(): Int {
            return textureBuffer.width
        }

        override fun getHeight(): Int {
            return textureBuffer.height
        }

        override fun getTransformMatrix(): Matrix? {
            return textureBuffer.transformMatrix
        }

        override fun getTextureId(): Int {
            return textureBuffer.textureId;
        }

        override fun toI420(): com.xodee.client.video.VideoFrameI420Buffer? {
            return textureBuffer.toI420()?.let { VideoFrameI420BufferAdapter.SdkToVideoClient(it) }
        }

        override fun getType(): com.xodee.client.video.VideoFrameTextureBuffer.Type {
            return when (textureBuffer.type) {
                VideoFrameTextureBuffer.Type.OES -> com.xodee.client.video.VideoFrameTextureBuffer.Type.OES
                VideoFrameTextureBuffer.Type.RGB -> com.xodee.client.video.VideoFrameTextureBuffer.Type.RGB
            }
        }

        override fun cropAndScale(
            cropX: Int,
            cropY: Int,
            cropWidth: Int,
            cropHeight: Int,
            scaleWidth: Int,
            scaleHeight: Int
        ): com.xodee.client.video.VideoFrameBuffer? {
            return SdkToVideoClient(
                textureBuffer.cropAndScale(
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight,
                    scaleWidth,
                    scaleHeight
                ) as VideoFrameTextureBuffer
            )
        }

        override fun release() {
            textureBuffer.release()
        }

        override fun retain() {
            textureBuffer.retain()
        }
    }

    class VideoClientToSdk(
        private val textureBuffer: com.xodee.client.video.VideoFrameTextureBuffer
    ) : VideoFrameTextureBuffer {
        override val width: Int
            get() = textureBuffer.width

        override val height: Int
            get() = textureBuffer.height

        override val textureId: Int
            get() = textureBuffer.textureId

        override val transformMatrix: Matrix?
            get() = textureBuffer.transformMatrix

        override val type: VideoFrameTextureBuffer.Type
            get() {
                return when (textureBuffer.type) {
                    com.xodee.client.video.VideoFrameTextureBuffer.Type.OES -> VideoFrameTextureBuffer.Type.OES
                    com.xodee.client.video.VideoFrameTextureBuffer.Type.RGB -> VideoFrameTextureBuffer.Type.RGB
                    else -> throw InvalidParameterException("Cannot have null type")
                }
            }

        override fun toI420(): VideoFrameI420Buffer? {
            return textureBuffer.toI420()?.let { VideoFrameI420BufferAdapter.VideoClientToSdk(it) }
        }

        override fun cropAndScale(
            cropX: Int,
            cropY: Int,
            cropWidth: Int,
            cropHeight: Int,
            scaleWidth: Int,
            scaleHeight: Int
        ): VideoFrameBuffer? {
            return VideoClientToSdk(
                textureBuffer.cropAndScale(
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight,
                    scaleWidth,
                    scaleHeight
                ) as com.xodee.client.video.VideoFrameTextureBuffer
            )
        }

        override fun release() {
            textureBuffer.release()
        }

        override fun retain() {
            textureBuffer.retain()
        }
    }
}