/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.device

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * [DeviceController] keeps track of the devices being used for audio device
 * (e.g. built-in speaker), video input (e.g. camera)).
 * The list functions return [MediaDevice] objects.
 * Changes in device availability are broadcast to any registered
 * [DeviceChangeObserver].
 */
interface DeviceController {
    /**
     * Lists currently available audio devices.
     *
     * @return a list of currently available audio devices.
     */
    fun listAudioDevices(): List<MediaDevice>

    /**
     * Selects an audio device to use.
     *
     * @param mediaDevice the audio device selected to use.
     */
    fun chooseAudioDevice(mediaDevice: MediaDevice)

    /**
     * Get the active local audio device in the meeting, return null if there isn't any.
     *
     * NOTE: This requires Android API 24 and above
     *
     * @return the active local audio device
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun getActiveAudioDevice(): MediaDevice?

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
     * Adds an observer to receive callbacks about device changes.
     *
     * @param observer device change observer
     */
    fun addDeviceChangeObserver(observer: DeviceChangeObserver)

    /**
     * Removes an observer to stop receiving callbacks about device changes.
     *
     * @param observer device change observer
     */
    fun removeDeviceChangeObserver(observer: DeviceChangeObserver)
}
