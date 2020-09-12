package com.amazonaws.services.chime.sdk.meetings.audiovideo.video


class VideoFrame(
    /**
     * State of video tile
     */
    var width: Int,
    /**
     * View which will be used to render the Video Frame
     */
    var height: Int,
    /**
     * State of video tile
     */
    var timestamp: Long,
    /**
     * View which will be used to render the Video Frame
     */
    var buffer: VideoFrameBuffer,

    /**
     * View which will be used to render the Video Frame
     */
    var rotation: Int = 0
) {


    fun getRotatedWidth(): Int {
        return if (rotation % 180 == 0) {
            buffer.getWidth()
        } else buffer.getHeight()
    }

    fun getRotatedHeight(): Int {
        return if (rotation % 180 == 0) {
            buffer.getHeight()
        } else buffer.getWidth()
    }
}