package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

interface VideoFrameBuffer {
    val width: Int
    val height: Int

    fun toI420(): VideoFrameI420Buffer?
    fun cropAndScale(
        cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int
    ): VideoFrameBuffer?

    fun retain()
    fun release()
}