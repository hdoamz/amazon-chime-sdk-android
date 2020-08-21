package com.amazonaws.services.chime.sdk.meetings.device

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import android.util.Size
import com.amazonaws.services.chime.sdk.meetings.internal.video.VideoClientController
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlin.math.max
import kotlin.math.roundToInt

class DefaultVideoDeviceController(
    private val context: Context,
    private val videoClientController: VideoClientController,
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
    private val logger: Logger
    ) : VideoDeviceController {

    private val NANO_SECONDS_PER_SECOND = 1.0e9

    override fun getActiveCamera(): MediaDevice? {
        return null
    }

    override fun switchCamera() {
        return
    }

    override fun listVideoDevices(): List<MediaDevice> {
        return cameraManager.cameraIdList.map { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING)?.let {
                return@map MediaDevice(id, MediaDeviceType.fromCameraMetadata(it))
            }
            return@map MediaDevice(id, MediaDeviceType.OTHER)
        }
    }

    override fun getSupportedVideoCaptureFormats(mediaDevice: MediaDevice): List<VideoDeviceFormat> {
        val characteristics = cameraManager.getCameraCharacteristics(mediaDevice.label)

        val framerateRanges = getSupportedFramerateRanges(characteristics)
        val defaultMaxFps = framerateRanges.fold(0) { currentMax, range -> max(currentMax, range.upper) }

        val sizes = getSupportedSizes(characteristics)

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
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
                if (minFrameDurationNs == 0L) defaultMaxFps else (NANO_SECONDS_PER_SECOND / minFrameDurationNs).roundToInt() * 1000
            VideoDeviceFormat(size.width, size.height, Range(0, maxFps))
        }
    }

    override fun chooseVideoDevice(mediaDevice: MediaDevice, format: VideoDeviceFormat) {
        TODO("Not yet implemented")
    }

    fun getSupportedSizes(cameraCharacteristics: CameraCharacteristics): List<Size> {
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

    fun getSupportedFramerateRanges(cameraCharacteristics: CameraCharacteristics): List<Range<Int>> {
        val arrayRanges = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return emptyList()
        val unitFactor = if(arrayRanges.isNotEmpty() && arrayRanges[0].upper < 1000) 1000 else 1

        return arrayRanges.map { range ->
            Range(range.lower * unitFactor, range.upper * unitFactor)
        }
    }
}