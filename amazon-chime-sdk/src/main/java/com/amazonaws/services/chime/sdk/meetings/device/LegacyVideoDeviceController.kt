package com.amazonaws.services.chime.sdk.meetings.device

import com.amazonaws.services.chime.sdk.meetings.internal.video.VideoClientController

class LegacyVideoDeviceController(
    private val videoClientController: VideoClientController
) : VideoDeviceController {
    override fun getActiveCamera(): MediaDevice? {
        val activeCamera = videoClientController.getActiveCamera()
        return activeCamera?.let {
            MediaDevice(
                activeCamera.name,
                if (activeCamera.isFrontFacing) MediaDeviceType.VIDEO_FRONT_CAMERA else MediaDeviceType.VIDEO_BACK_CAMERA
            )
        }
    }

    override fun switchCamera() {
        videoClientController.switchCamera()
    }

    override fun listVideoDevices(): List<MediaDevice> {
        throw NotImplementedError("This function is not implemented in LegacyVideoDeviceController")
    }

    override fun getSupportedVideoCaptureFormats(mediaDevice: MediaDevice): List<VideoDeviceFormat> {
        throw NotImplementedError("This function is not implemented in LegacyVideoDeviceController")
    }

    override fun chooseVideoDevice(mediaDevice: MediaDevice, format: VideoDeviceFormat) {
        throw NotImplementedError("This function is not implemented in LegacyVideoDeviceController")
    }
}