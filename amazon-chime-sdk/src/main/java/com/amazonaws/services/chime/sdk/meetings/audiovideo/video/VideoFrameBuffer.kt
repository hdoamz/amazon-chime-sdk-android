package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

/**
 * [VideoFrameBuffer] is a buffer which contains a single video frame
 */
interface VideoFrameBuffer {
    /**
     * Width of the video frame buffer
     */
    val width: Int

    /**
     * Height of the video frame buffer
     */
    val height: Int

    /**
     * Retain the video frame buffer.  retain/release may not always be necessary unless the buffer
     * is holding onto resourses that it needs to dispose or release itself
     */
    fun retain()

    /**
     * Retain the video frame buffer.  retain/release may be no-ops unless the buffer
     * is holding onto resourses that it needs to dispose or release itself
     */
    fun release()
}