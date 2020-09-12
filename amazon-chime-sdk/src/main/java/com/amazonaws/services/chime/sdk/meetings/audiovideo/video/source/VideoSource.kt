package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSink

interface VideoSource {
    val contentHint: ContentHint

    fun addSink(sink: VideoSink)
    fun removeSink(sink: VideoSink)
}