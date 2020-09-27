package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer

/**
 * [GlVideoFrameBufferConverter] is a helper class for converting between GL based video buffers (i.e. VideoFrameTextureBuffer)
 * and host memory buffers (i.e. VideoFrameI420Buffer)
 */
interface GlVideoFrameTextureBufferConverter {
    /**
     * Convert from texture buffer to I420 buffer
     */
    fun toI420(textureBuffer: VideoFrameTextureBuffer): VideoFrameI420Buffer

    /**
     * Convert from I420 buffer to texture buffer
     */
    fun fromI420(i420Buffer: VideoFrameI420Buffer): VideoFrameTextureBuffer
}