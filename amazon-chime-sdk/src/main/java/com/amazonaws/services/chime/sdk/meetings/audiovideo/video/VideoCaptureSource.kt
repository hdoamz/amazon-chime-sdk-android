package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

interface VideoCaptureSource : VideoSource {
    fun start()
    fun stop()
}