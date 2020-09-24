package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice

interface CameraCaptureSource :  VideoCaptureSource {
    var device: MediaDevice
    var format: VideoCaptureFormat

    fun switchCamera()
}