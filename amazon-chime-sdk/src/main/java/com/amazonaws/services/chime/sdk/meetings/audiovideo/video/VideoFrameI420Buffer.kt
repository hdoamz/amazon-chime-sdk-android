package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import java.nio.ByteBuffer

/**
 * [VideoFrameI420Buffer] is a buffer which contains a single video frame in planar YUV in CPU memory
 */
interface VideoFrameI420Buffer : VideoFrameBuffer {
    /**
     * Y plane data of video frame in memory
     */
    val dataY: ByteBuffer?

    /**
     * U plane data of video frame in memory
     */
    val dataU: ByteBuffer?

    /**
     * V plane data of video frame in memory
     */
    val dataV: ByteBuffer?

    /**
     * Stride of Y plane of video frame
     */
    val strideY: Int

    /**
     * Stride of U plane of video frame
     */
    val strideU: Int

    /**
     * Stride of V plane of video frame
     */
    val strideV: Int
}