package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import java.nio.ByteBuffer

interface VideoFrameI420Buffer : VideoFrameBuffer {
    fun dataY(): ByteBuffer?
    fun dataU(): ByteBuffer?
    fun dataV(): ByteBuffer?
    fun strideY(): Int
    fun strideU(): Int
    fun strideV(): Int
}