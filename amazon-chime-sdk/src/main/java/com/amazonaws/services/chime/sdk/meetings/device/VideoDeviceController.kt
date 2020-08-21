package com.amazonaws.services.chime.sdk.meetings.device

interface VideoDeviceController {
    /**
     * Get the active local camera in the meeting, return null if there isn't any.
     *
     * @return the active local camera
     */
    fun getActiveCamera(): MediaDevice?

    /**
     * Switch between front and back camera in the meeting.
     */
    fun switchCamera()

    /**
     * Lists currently available video devices.
     *
     * @return a list of currently available video devices.
     */
    fun listVideoDevices(): List<MediaDevice>

    /**
     * Lists currently available video devices.
     *
     * @return a list of currently available video devices.
     */
    fun getSupportedVideoCaptureFormats(mediaDevice: MediaDevice): List<VideoDeviceFormat>

    /**
     * Selects an video device to use.
     *
     * @param mediaDevice the video device selected to use.
     */
    fun chooseVideoDevice(mediaDevice: MediaDevice, format: VideoDeviceFormat)
}