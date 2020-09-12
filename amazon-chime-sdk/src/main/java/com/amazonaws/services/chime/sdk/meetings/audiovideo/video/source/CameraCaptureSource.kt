package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

interface CameraCaptureSource :  VideoCaptureSource {
    fun setDeviceId(id: String)
}