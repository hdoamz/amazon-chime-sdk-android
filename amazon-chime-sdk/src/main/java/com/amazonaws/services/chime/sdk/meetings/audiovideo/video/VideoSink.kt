package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

/**
 * [VideoSink] implemetations consume video frames, typically from a [VideoSource]
 */
interface VideoSink {
    /**
     * Receive a video frame from some upstream source
     *
     * @param frame: [VideoFrame] - New video frame to consume
     */
    fun onVideoFrameReceived(frame: VideoFrame)
}