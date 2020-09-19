package com.amazonaws.services.chime.sdk.meetings.internal.video

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import java.nio.ByteBuffer

class VideoFrameI420BufferAdapter {
    class SdkToVideoClient(
        val i420Buffer: VideoFrameI420Buffer
    ) : com.xodee.client.video.VideoFrameI420Buffer {
        override fun getWidth(): Int {
            return i420Buffer.width
        }

        override fun getHeight(): Int {
            return i420Buffer.height
        }

        override fun getDataY(): ByteBuffer? {
            return i420Buffer.dataY
        }

        override fun getDataU(): ByteBuffer? {
            return i420Buffer.dataU
        }

        override fun getDataV(): ByteBuffer? {
            return i420Buffer.dataV
        }

        override fun getStrideY(): Int {
            return i420Buffer.strideY
        }

        override fun getStrideU(): Int {
            return i420Buffer.strideU
        }

        override fun getStrideV(): Int {
            return i420Buffer.strideV
        }


        override fun toI420(): com.xodee.client.video.VideoFrameI420Buffer? {
            return this
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
                i420Buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight) as VideoFrameI420Buffer)
        }

        override fun retain() {
            i420Buffer.retain()
        }

        override fun release() {
            i420Buffer.release()
        }
    }

    class VideoClientToSdk(
        private val i420Buffer: com.xodee.client.video.VideoFrameI420Buffer
    ) : VideoFrameI420Buffer {
        override val width: Int
            get() {
                return i420Buffer.width
            }

        override val height: Int
            get() {
                return i420Buffer.height
            }

        override val dataY: ByteBuffer?
            get() {
                return i420Buffer.dataY
            }

        override val dataU: ByteBuffer?
            get() {
                return i420Buffer.dataU
            }

        override val dataV: ByteBuffer?
            get() {
                return i420Buffer.dataV
            }

        override val strideY: Int
            get() {
                return i420Buffer.strideY
            }

        override val strideU: Int
            get() {
                return i420Buffer.strideU
            }

        override val strideV: Int
            get() {
                return i420Buffer.strideV
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
            return VideoClientToSdk(
                i420Buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight) as com.xodee.client.video.VideoFrameI420Buffer)
        }

        override fun retain() {
            i420Buffer.retain()
        }

        override fun release() {
            i420Buffer.release()
        }
    }
}