package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer

/**
 * [GlVideoFrameBufferConverter] is a helper class for converting from GL based video buffers (i.e. VideoFrameTextureBuffer)
 * to host memory buffers (i.e. VideoFrameI420Buffer)
 */
interface GlVideoFrameConverter {
    /**
     * Convert from texture buffer to I420 buffer
     */
    fun toI420(textureBuffer: VideoFrameTextureBuffer): VideoFrameI420Buffer
}