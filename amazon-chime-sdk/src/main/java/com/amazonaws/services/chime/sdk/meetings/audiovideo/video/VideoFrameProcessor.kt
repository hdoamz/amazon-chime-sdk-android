package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

interface VideoFrameProcessor {
    fun process(frame: VideoFrame): VideoFrame
}