package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

interface VideoSink {
    fun onFrameCaptured(frame: VideoFrame)
}