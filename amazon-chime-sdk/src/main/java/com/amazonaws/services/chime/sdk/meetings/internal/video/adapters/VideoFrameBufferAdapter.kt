package com.amazonaws.services.chime.sdk.meetings.internal.video.adapters

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer

class VideoFrameBufferAdapter {
    class SDKToMedia(
        val buffer: VideoFrameBuffer
    ) : com.xodee.client.video.VideoFrameBuffer {

        override fun getWidth(): Int {
            return buffer.width
        }

        override fun getHeight(): Int {
            return buffer.height
        }

        override fun release() {
            buffer.release()
        }

        override fun retain() {
            buffer.retain()
        }
    }

    class MediaToSDK(
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

        override fun release() {
            buffer.release()
        }

        override fun retain() {
            buffer.retain()
        }
    }
}