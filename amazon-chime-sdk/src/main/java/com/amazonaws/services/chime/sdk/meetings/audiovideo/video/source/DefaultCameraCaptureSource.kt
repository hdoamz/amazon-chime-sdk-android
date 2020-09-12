package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.camera2.*
import android.opengl.EGL14
import android.opengl.EGLContext
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.DefaultVideoFrameTextureBuffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking


class DefaultCameraCaptureSource(
    private val context: Context,
    private val logger: Logger,
    sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : SurfaceTextureCaptureSource(logger, sharedEGLContext), CameraCaptureSource {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var cameraOrientation = 0
    private var isCameraFrontFacing = false

    private var currentDeviceId: String? = null

    private val TAG = "DefaultCameraCaptureSource"

    override val contentHint = ContentHint.Motion

    // Implement and store callbacks as private constants since we can't inherit from all of them

    val cameraCaptureSessionCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            logger.error(TAG, "onCaptureBufferLost")
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            logger.error(TAG, "onCaptureFailed")
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            logger.error(TAG, "onCaptureSequenceAborted")
        }

        override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            logger.error(TAG, "onCaptureSequenceCompleted")
        }
    }


    val cameraCaptureSessionStateCallback = object: CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            logger.info(TAG, "Camera capture session configured for session with device ID: ${session.device.id}")

            cameraCaptureSession = session
            cameraDevice?.let { device ->
                try {
                    val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

                    currentVideoCaptureFormat?.let {
                        captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(it.maxFps, it.maxFps)
                        )
                    }

                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
                    )
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                    chooseStabilizationMode(captureRequestBuilder)
                    chooseFocusMode(captureRequestBuilder)
                    captureRequestBuilder.addTarget(surface)
                    session.setRepeatingRequest(
                        captureRequestBuilder.build(), cameraCaptureSessionCaptureCallback, handler
                    )
                    logger.info(TAG, "Capture request completed with device ID: ${session.device.id}")
                } catch (e: CameraAccessException) {
                    logger.error(TAG, "Failed to start capture request with device ID: ${session.device.id}")
                    return
                }
            }

            return
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            logger.error(TAG, "Camera session configuration failed with device ID: ${session.device.id}")
            session.close()
        }
    }


    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback()  {
        override fun onOpened(device: CameraDevice) {
            logger.info(TAG, "Camera device opened for ID ${device.id}")
            cameraDevice = device
            try {
                cameraDevice?.createCaptureSession(
                    listOf(surface), cameraCaptureSessionStateCallback, handler
                )
            } catch (e: CameraAccessException) {
                logger.info(TAG, "Exception encountered creating capture session: ${e.reason}")
                return
            }
        }

        override fun onClosed(device: CameraDevice) {
            logger.info(TAG, "Camera device closed for ID ${device.id}")
        }

        override fun onDisconnected(device: CameraDevice) {
            logger.info(TAG, "Camera device disconnected for ID ${device.id}")
            device.close()
        }

        override fun onError(device: CameraDevice, error: Int) {
            logger.info(TAG, "Camera device encountered error: $error for ID ${device.id}")
            onDisconnected(device)
        }
    }

    override fun setDeviceId(id: String) {
        handler.post {
            logger.info(TAG,"Setting capture device ID: $id")
            if (id == currentDeviceId) {
                logger.info(TAG, "Already using device ID: $currentDeviceId; ignoring")
                return@post
            }

            currentDeviceId = id

            currentVideoCaptureFormat?.let {
                stop()
                start(it)
            }
        }
    }

    override fun start(format: VideoCaptureFormat) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Missing necessary camera permissions")
        }
        super.start(format)
        setFrameProcessor(this)

        if (currentDeviceId == null && cameraManager.cameraIdList.isNotEmpty()) {
            currentDeviceId = cameraManager.cameraIdList[0]
        }

        currentDeviceId?.let {id ->
            logger.info(TAG, "Starting camera capture with format: $currentVideoCaptureFormat and ID: $id")
            cameraCharacteristics = cameraManager.getCameraCharacteristics(id).also {
                cameraOrientation = it.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                isCameraFrontFacing = it.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            }

            cameraManager.openCamera(id, cameraDeviceStateCallback, handler)
        }
    }

    override fun stop() {
        logger.info(TAG, "Stopping camera capture source")
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            // Stop Surface capture source
            super.stop()

            cameraCaptureSession?.close()
            cameraCaptureSession = null

            cameraDevice?.close()
            cameraDevice = null

            currentVideoCaptureFormat = null
        }
    }

    override fun process(frame: VideoFrame): VideoFrame {
        return VideoFrame(
            frame.width, frame.height, frame.timestamp,
            createTextureBufferWithModifiedTransformMatrix(frame.buffer as DefaultVideoFrameTextureBuffer, !isCameraFrontFacing, -cameraOrientation),
            getFrameOrientation()
        )
    }

    private fun chooseStabilizationMode(captureRequestBuilder: CaptureRequest.Builder) {
        val availableOpticalStabilization: IntArray? = cameraCharacteristics?.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )
        if (availableOpticalStabilization != null) {
            for (mode in availableOpticalStabilization) {
                if (mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    captureRequestBuilder[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    captureRequestBuilder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    logger.info(TAG, "Using optical stabilization.")
                    return
                }
            }
        }
        // If no optical mode is available, try software.
        val availableVideoStabilization: IntArray? = cameraCharacteristics?.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        if (availableVideoStabilization != null) {
            for (mode in availableVideoStabilization) {
                if (mode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    captureRequestBuilder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    captureRequestBuilder[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    logger.info(TAG, "Using video stabilization.")
                    return
                }
            }
        }
        logger.info(TAG, "Stabilization not available.")
    }

    private fun chooseFocusMode(captureRequestBuilder: CaptureRequest.Builder) {
        val availableFocusModes =
            cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (availableFocusModes != null) {
            for (mode in availableFocusModes) {
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                logger.info(TAG, "Using continuous video auto-focus.");
                return;
            }
        }
        logger.info(TAG, "Auto-focus is not available.");
    }
    private fun getFrameOrientation(): Int {
        val wm =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var rotation =  when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_0 -> 0
            else -> 0
        }
        if (!isCameraFrontFacing) {
            rotation = 360 - rotation
        }
        return (cameraOrientation + rotation) % 360
    }

    private fun createTextureBufferWithModifiedTransformMatrix(
        buffer: DefaultVideoFrameTextureBuffer, mirror: Boolean, rotation: Int
    ): VideoFrameTextureBuffer {
        val transformMatrix = Matrix()
        // Perform mirror and rotation around (0.5, 0.5) since that is the center of the texture.
        transformMatrix.preTranslate( /* dx= */0.5f,  /* dy= */0.5f)
        if (mirror) {
            transformMatrix.preScale( /* sx= */-1f,  /* sy= */1f)
        }
        transformMatrix.preRotate(rotation.toFloat())
        transformMatrix.preTranslate( /* dx= */-0.5f,  /* dy= */-0.5f)

        // The width and height are not affected by rotation since Camera2Session has set them to the
        // value they should be after undoing the rotation.
        return buffer.applyTransformMatrix(transformMatrix, buffer.getWidth(), buffer.getHeight())
    }
}