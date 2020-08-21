package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

interface VideoSource {
    fun addSink(sink: VideoSink)
    fun removeSink(sink: VideoSink)
}