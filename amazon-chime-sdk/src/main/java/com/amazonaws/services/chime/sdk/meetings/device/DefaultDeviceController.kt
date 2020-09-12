/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.opengl.EGL14
import android.opengl.EGLContext
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.annotation.VisibleForTesting
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoSink
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCore
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source.DefaultCameraCaptureSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source.VideoCaptureFormat
import com.amazonaws.services.chime.sdk.meetings.internal.audio.AudioClientController
import com.amazonaws.services.chime.sdk.meetings.internal.utils.ObserverUtils
import com.amazonaws.services.chime.sdk.meetings.internal.video.VideoClientController
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.xodee.client.audio.audioclient.AudioClient
import kotlin.math.max
import kotlin.math.roundToInt


class DefaultDeviceController(
    private val context: Context,
    private val audioClientController: AudioClientController,
    private val videoClientController: VideoClientController,
    private val logger: Logger,
    private val sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT,
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
    private val buildVersion: Int = Build.VERSION.SDK_INT
) : DeviceController {
    private val deviceChangeObservers = mutableSetOf<DeviceChangeObserver>()

    private val eglCore = EglCore(sharedEGLContext, logger)

    private var cameraCaptureVideoSource: DefaultCameraCaptureSource? = null
    private var currentCameraCaptureMediaDevice: MediaDevice? = null
    private var currentCameraCaptureFormat: VideoCaptureFormat? = null

    // TODO: remove code blocks for lower API level after the minimum SDK version becomes 23
    private val AUDIO_MANAGER_API_LEVEL = 23
    private val CAMERA_MANAGER_API_LEVEL = 23

    private val defaultVideoCaptureFormat = VideoCaptureFormat(1280, 720, 15)

    private val TAG = "DefaultDeviceController"

    init {
        @SuppressLint("NewApi")
        if (buildVersion >= AUDIO_MANAGER_API_LEVEL) {
            audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    notifyAudioDeviceChange()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    notifyAudioDeviceChange()
                }
            }, null)
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // There is gap between notification and audioManager recognizing bluetooth devices
                    if (intent?.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                        Thread.sleep(1000)
                    }
                    notifyAudioDeviceChange()
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
            context.registerReceiver(
                receiver, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            )
        }

        if (buildVersion >= CAMERA_MANAGER_API_LEVEL) {
            cameraManager.registerAvailabilityCallback(object :
                CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    notifyVideoDeviceChange()
                }

                override fun onCameraUnavailable(cameraId: String) {
                    notifyVideoDeviceChange()
                }
            }, null)
        }
    }

    override fun listAudioDevices(): List<MediaDevice> {
        @SuppressLint("NewApi")
        if (buildVersion >= AUDIO_MANAGER_API_LEVEL) {
            var isWiredHeadsetOn = false
            var isHandsetAvailable = false
            val handsetDevicesInfo = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_TELEPHONY
            )

            val audioDevices = mutableListOf<MediaDevice>()
            for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                // System will select wired headset over receiver
                // so we want to filter receiver out when wired headset is connected
                if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                ) {
                    isWiredHeadsetOn = true
                }

                // Return only one handset device to avoid confusion
                if (handsetDevicesInfo.contains(device.type)) {
                    if (isHandsetAvailable) continue
                    else {
                        isHandsetAvailable = true
                    }
                }

                audioDevices.add(
                    MediaDevice(
                        "${device.productName} (${getReadableType(device.type)})",
                        MediaDeviceType.fromAudioDeviceInfo(
                            device.type
                        )
                    )
                )
            }
            return if (isWiredHeadsetOn) audioDevices.filter { it.type != MediaDeviceType.AUDIO_HANDSET } else audioDevices
        } else {
            val res = mutableListOf<MediaDevice>()
            res.add(
                MediaDevice(
                    getReadableType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                    MediaDeviceType.AUDIO_BUILTIN_SPEAKER
                )
            )
            if (audioManager.isWiredHeadsetOn) {
                res.add(
                    MediaDevice(
                        getReadableType(AudioDeviceInfo.TYPE_WIRED_HEADSET),
                        MediaDeviceType.AUDIO_WIRED_HEADSET
                    )
                )
            } else {
                res.add(
                    MediaDevice(
                        getReadableType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
                        MediaDeviceType.AUDIO_HANDSET
                    )
                )
            }
            if (audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn) {
                res.add(
                    MediaDevice(
                        getReadableType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                        MediaDeviceType.AUDIO_BLUETOOTH
                    )
                )
            }
            return res
        }
    }

    override fun chooseAudioDevice(mediaDevice: MediaDevice) {
        setupAudioDevice(mediaDevice.type)

        val route = when (mediaDevice.type) {
            MediaDeviceType.AUDIO_BUILTIN_SPEAKER -> AudioClient.SPK_STREAM_ROUTE_SPEAKER
            MediaDeviceType.AUDIO_BLUETOOTH -> AudioClient.SPK_STREAM_ROUTE_BT_AUDIO
            MediaDeviceType.AUDIO_WIRED_HEADSET -> AudioClient.SPK_STREAM_ROUTE_HEADSET
            else -> AudioClient.SPK_STREAM_ROUTE_RECEIVER
        }
        audioClientController.setRoute(route)
    }

    private fun setupAudioDevice(type: MediaDeviceType) {
        when (type) {
            MediaDeviceType.AUDIO_BUILTIN_SPEAKER ->
                audioManager.apply {
                    mode = AudioManager.MODE_IN_COMMUNICATION
                    isSpeakerphoneOn = true
                    isBluetoothScoOn = false
                    stopBluetoothSco()
                }
            MediaDeviceType.AUDIO_BLUETOOTH ->
                audioManager.apply {
                    mode = AudioManager.MODE_IN_COMMUNICATION
                    isSpeakerphoneOn = false
                    startBluetoothSco()
                }
            else ->
                audioManager.apply {
                    mode = AudioManager.MODE_IN_COMMUNICATION
                    isSpeakerphoneOn = false
                    isBluetoothScoOn = false
                    stopBluetoothSco()
                }
        }
    }

    private fun getReadableType(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphone"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_TELEPHONY -> "Handset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Unknown (AudioDeviceInfo: $type)"
        }
    }

    override fun getActiveCamera(): MediaDevice? {
        return currentCameraCaptureMediaDevice
    }

    override fun switchCamera() {
        // Note this will also switch from external cameras to the front camera
        logger.info(TAG, "Switching camera from $currentCameraCaptureMediaDevice")
        val newType =
            if (currentCameraCaptureMediaDevice?.type != MediaDeviceType.VIDEO_FRONT_CAMERA) {
                MediaDeviceType.VIDEO_FRONT_CAMERA
            } else {
                MediaDeviceType.VIDEO_BACK_CAMERA
            }

        val oppositeFacingDevices = listVideoDevices().filter { it.type == newType }

        currentCameraCaptureMediaDevice = oppositeFacingDevices.firstOrNull()?.also {
            cameraCaptureVideoSource?.setDeviceId(it.id)
        }
    }

    override fun listVideoDevices(): List<MediaDevice> {
        return listVideoDevices(cameraManager)

    }

    override fun getSupportedVideoCaptureFormats(mediaDevice: MediaDevice): List<VideoCaptureFormat> {
        return getSupportedVideoCaptureFormats(cameraManager, mediaDevice)
    }

    override fun startVideoCapture(mediaDevice: MediaDevice?, format: VideoCaptureFormat?) {
        if (cameraCaptureVideoSource != null) {
            logger.info(TAG, "startVideoCapture called with active capture session, stopping")
            cameraCaptureVideoSource?.stop()
        }

        logger.info(TAG, "Creating new camera capture video source")
        cameraCaptureVideoSource =
            DefaultCameraCaptureSource(
                context,
                logger,
                eglCore.eglContext
            )
        if (cameraCaptureVideoSource != null) {
            videoClientController.chooseVideoSource(cameraCaptureVideoSource)
        }


        currentCameraCaptureMediaDevice = mediaDevice ?: currentCameraCaptureMediaDevice
        currentCameraCaptureMediaDevice = currentCameraCaptureMediaDevice ?: {
            logger.info(TAG, "No device selected, using default device")
            val devices = listVideoDevices()
            val frontFacingDevices = devices.filter {
                it.type == MediaDeviceType.VIDEO_FRONT_CAMERA
            }

            when {
                frontFacingDevices.isNotEmpty() -> frontFacingDevices[0]
                devices.isNotEmpty() -> devices[0]
                else -> null
            }
        }()
        currentCameraCaptureMediaDevice?.let { device ->
            cameraCaptureVideoSource?.setDeviceId(device.id)
        }

        currentCameraCaptureFormat = format ?: currentCameraCaptureFormat
        cameraCaptureVideoSource?.start(format ?: defaultVideoCaptureFormat)
    }

    override fun stopVideoCapture() {
        cameraCaptureVideoSource?.stop()
    }

    override fun bindVideoCaptureOutput(videoSink: VideoSink) {
        cameraCaptureVideoSource?.addSink(videoSink)
    }

    override fun addDeviceChangeObserver(observer: DeviceChangeObserver) {
        deviceChangeObservers.add(observer)
    }

    override fun removeDeviceChangeObserver(observer: DeviceChangeObserver) {
        deviceChangeObservers.remove(observer)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun notifyAudioDeviceChange() {
        ObserverUtils.notifyObserverOnMainThread(deviceChangeObservers) {
            it.onAudioDeviceChanged(
                listAudioDevices()
            )
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun notifyVideoDeviceChange() {
        ObserverUtils.notifyObserverOnMainThread(deviceChangeObservers) {
            it.onAudioDeviceChanged(
                listAudioDevices()
            )
        }
    }

    companion object {
        private val NANO_SECONDS_PER_SECOND = 1.0e9

        fun listVideoDevices(cameraManager: CameraManager): List<MediaDevice> {
            return cameraManager.cameraIdList.map { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING)?.let {
                    val type = MediaDeviceType.fromCameraMetadata(it)
                    return@map MediaDevice("$id ($type)", type, id)
                }
                return@map MediaDevice("$id ($MediaDeviceType.OTHER)", MediaDeviceType.OTHER)
            }
        }

        fun getSupportedVideoCaptureFormats(
            cameraManager: CameraManager,
            mediaDevice: MediaDevice
        ): List<VideoCaptureFormat> {
            val characteristics = cameraManager.getCameraCharacteristics(mediaDevice.id)

            val framerateRanges = getSupportedFramerateRanges(characteristics)
            val defaultMaxFps =
                framerateRanges.fold(0) { currentMax, range -> max(currentMax, range.upper) }

            val sizes = getSupportedSizes(characteristics)

            val streamMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return emptyList()

            return sizes.map { size ->
                var minFrameDurationNs: Long = 0
                try {
                    minFrameDurationNs = streamMap.getOutputMinFrameDuration(
                        SurfaceTexture::class.java, Size(size.width, size.height)
                    )
                } catch (e: Exception) {
                    // getOutputMinFrameDuration() is not supported on all devices. Ignore silently.
                }
                val maxFps =
                    if (minFrameDurationNs == 0L) defaultMaxFps else (NANO_SECONDS_PER_SECOND / minFrameDurationNs).roundToInt()
                VideoCaptureFormat(
                    size.width,
                    size.height,
                    maxFps
                )
            }
        }

        private fun getSupportedSizes(cameraCharacteristics: CameraCharacteristics): List<Size> {
            val streamMap =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return emptyList()
            val supportLevel =
                cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: return emptyList()
            val nativeSizes = streamMap.getOutputSizes(SurfaceTexture::class.java)
                ?: return emptyList()

            // Video may be stretched pre LMR1 on legacy implementations.
            // Filter out formats that have different aspect ratio than the sensor array.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1
                && supportLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            ) {
                val activeArraySize =
                    cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?: return emptyList()
                return nativeSizes.filter { size ->
                    if (activeArraySize.width() * size.height != activeArraySize.height() * size.width) {
                        return@filter false
                    }
                    return@filter true
                }
            }
            return nativeSizes.toList()
        }

        private fun getSupportedFramerateRanges(cameraCharacteristics: CameraCharacteristics): List<Range<Int>> {
            val arrayRanges =
                cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?: return emptyList()
            val unitFactor =
                if (arrayRanges.isNotEmpty() && arrayRanges[0].upper < 1000) 1 else 1000

            return arrayRanges.map { range ->
                Range(range.lower / unitFactor, range.upper / unitFactor)
            }
        }
    }
}
