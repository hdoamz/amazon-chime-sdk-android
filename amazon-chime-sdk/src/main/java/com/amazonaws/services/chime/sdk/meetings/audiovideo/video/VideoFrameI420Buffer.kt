package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import java.nio.ByteBuffer

interface VideoFrameI420Buffer : VideoFrameBuffer {
    val dataY: ByteBuffer?
    val dataU: ByteBuffer?
    val dataV: ByteBuffer?
    val strideY: Int
    val strideU: Int
    val strideV: Int
}