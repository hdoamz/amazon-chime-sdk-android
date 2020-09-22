package com.amazonaws.services.chime.sdk.meetings.internal.video.adapters

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer

class VideoFrameBufferAdapter {
    class SdkToVideoClient(
        val buffer: VideoFrameBuffer
    ) : com.xodee.client.video.VideoFrameBuffer {

        override fun getWidth(): Int {
            return buffer.width
        }

        override fun getHeight(): Int {
            return buffer.height
        }

        override fun toI420(): com.xodee.client.video.VideoFrameI420Buffer? {
            return buffer.toI420()?.let {
                VideoFrameI420BufferAdapter.SdkToVideoClient(
                    it
                )
            }
        }

        override fun cropAndScale(
            cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int
        ) : com.xodee.client.video.VideoFrameBuffer? {
            return buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight)?.let {
                SdkToVideoClient(
                    it
                )
            }
        }

        override fun release() {
            buffer.release()
        }

        override fun retain() {
            buffer.retain()
        }
    }

    class VideoClientToSdk(
        val buffer: com.xodee.client.video.VideoFrameBuffer
    ) : VideoFrameBuffer {

        override val width: Int
            get() {
                return buffer.width
            }

        override val height: Int
            get() {
                return buffer.height
            }

        override fun toI420(): VideoFrameI420Buffer? {
            return buffer.toI420()?.let {
                VideoFrameI420BufferAdapter.VideoClientToSdk(
                    it
                )
            }
        }

        override fun cropAndScale(
            cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int
        ) : VideoFrameBuffer? {
            return buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight)?.let {
                VideoClientToSdk(
                    it
                )
            }
        }

        override fun release() {
            buffer.release()
        }

        override fun retain() {
            buffer.retain()
        }
    }
}