package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.opengl.EGLContext
import android.util.Range
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger


class CameraCaptureVideoSource(
    private val context: Context,
    private val logger: Logger,
    sharedEGLContext: EGLContext? = null
    ) : SurfaceTextureVideoSource(logger, sharedEGLContext) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraCharacteristics: CameraCharacteristics

    private val TAG = "CameraCaptureVideoSource"

    fun start() {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Missing necessary camera permissions")
        }

        val cameraId = cameraManager.cameraIdList[0]
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        logger.info(TAG, "Starting local video")

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
                logger.info(TAG, "Camera capture session configured")

                cameraCaptureSession = session
                try {
                    val captureRequestBuilder: CaptureRequest.Builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    // Set auto exposure fps range.
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(
                            15,
                            15
                        )
                    )
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
                } catch (e: CameraAccessException) {
                    logger.error(TAG, "Failed to start capture request")
                    return
                }
                return
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                logger.error(TAG, "Camera session configuration failed")
                session.close()
            }
        }

        val cameraDeviceStateCallback = object : CameraDevice.StateCallback()  {
            override fun onOpened(device: CameraDevice) {
                logger.info(TAG, "Camera device opened")
                cameraDevice = device
                try {
                    cameraDevice.createCaptureSession(
                        listOf(surface), cameraCaptureSessionStateCallback, handler
                    )
                } catch (e: CameraAccessException) {
                    return
                }
            }
            override fun onClosed(device: CameraDevice) {
                logger.info(TAG, "Camera device closed")
            }
            override fun onDisconnected(device: CameraDevice) {
                logger.info(TAG, "Camera device disconnected")
                device.close()
            }
            override fun onError(device: CameraDevice, error: Int) {
                logger.info(TAG, "Camera device encountered error: $error")
                onDisconnected(device)
            }
        }

        cameraManager.openCamera(cameraId, cameraDeviceStateCallback, handler)
    }

    private fun chooseStabilizationMode(captureRequestBuilder: CaptureRequest.Builder) {
        val availableOpticalStabilization: IntArray? = cameraCharacteristics.get(
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
        val availableVideoStabilization: IntArray? = cameraCharacteristics.get(
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
            cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
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
}