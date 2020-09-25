package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.hardware.camera2.*
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.*
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCore
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.YuvConverter
import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice
import com.amazonaws.services.chime.sdk.meetings.device.MediaDeviceType
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking


class DefaultCameraCaptureSource(
    private val context: Context,
    private val logger: Logger,
    private val sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : CameraCaptureSource, VideoSink {
    private val thread: HandlerThread = HandlerThread("DefaultCameraCaptureSource")
    private lateinit var eglCore: EglCore
    val handler: Handler

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var cameraOrientation = 0
    private var isCameraFrontFacing = false
    var currentVideoCaptureFormat: VideoCaptureFormat? = null

    private var currentDeviceId: String? = null

    private var surfaceTextureSource: SurfaceTextureCaptureSource? = null
    private var convertToCPU = false

    private var observers = mutableSetOf<CaptureSourceObserver>()
    private var sinks = mutableSetOf<VideoSink>()

    private val TAG = "DefaultCameraCaptureSource"

    override val contentHint = ContentHint.Motion

    init {
        if (true){//sharedEGLContext == EGL14.EGL_NO_CONTEXT) {
            logger.info(TAG, "No shared EGL context provided, will convert all frames to CPU memory")
            convertToCPU = true
        }
        thread.start()
        handler = Handler(thread.looper)

        runBlocking(handler.asCoroutineDispatcher().immediate) {
            eglCore =
                EglCore(
                    EGL14.EGL_NO_CONTEXT,
                    logger = logger
                )
        }
    }

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
                            Range(it.maxFramerate, it.maxFramerate)
                        )
                    }

                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
                    )
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                    chooseStabilizationMode(captureRequestBuilder)
                    chooseFocusMode(captureRequestBuilder)
                    surfaceTextureSource?.surface?.let { captureRequestBuilder.addTarget(it) }
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
                    listOf(surfaceTextureSource?.surface), cameraCaptureSessionStateCallback, handler
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

    override var device: MediaDevice
        get() {
            if (currentDeviceId == null && cameraManager.cameraIdList.isNotEmpty()) {
                currentDeviceId = cameraManager.cameraIdList[0]
            }
            return MediaDevice("blah", MediaDeviceType.VIDEO_BACK_CAMERA, currentDeviceId ?: "")
        }
        set(value) {
            handler.post {
                logger.info(TAG,"Setting capture device ID: $value.id")
                if (value.id == currentDeviceId) {
                    logger.info(TAG, "Already using device ID: $currentDeviceId; ignoring")
                    return@post
                }

                currentDeviceId = value.id

                currentVideoCaptureFormat?.let {
                    stop()
                    start(it)
                }
            }
        }
    override var format: VideoCaptureFormat
        get() {
            return currentVideoCaptureFormat ?: return VideoCaptureFormat(0,0,0)
        }
        set(value) {
            stop()
            start(value)
        }

    override fun switchCamera() {
        TODO("Not yet implemented")
    }

    override fun start(format: VideoCaptureFormat) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Missing necessary camera permissions")
        }

        surfaceTextureSource = SurfaceTextureCaptureSource(logger, handler, eglCore.eglContext)
        surfaceTextureSource!!.start(format)
        surfaceTextureSource!!.addVideoSink(this)

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
        val self: VideoSink = this
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            // Stop Surface capture source
            surfaceTextureSource!!.removeVideoSink(self)
            surfaceTextureSource!!.stop()
            surfaceTextureSource!!.release()

            cameraCaptureSession?.close()
            cameraCaptureSession = null

            cameraDevice?.close()
            cameraDevice = null

            currentVideoCaptureFormat = null
        }
    }

    override fun onVideoFrameReceived(frame: VideoFrame) {
        var processedBuffer: DefaultVideoFrameTextureBuffer =
            createTextureBufferWithModifiedTransformMatrix(frame.buffer as DefaultVideoFrameTextureBuffer, !isCameraFrontFacing, -cameraOrientation)
//        if (convertToCPU) {
//            processedBuffer = YuvConverter().convert(processedBuffer as VideoFrameTextureBuffer)
//        }
        val processedFrame = processedBuffer.toI420()?.let {
            VideoFrame(
                frame.timestamp,
                it,
                getFrameOrientation()
            )
        }
        processedBuffer?.release()
        sinks.forEach{
            if (processedFrame != null) {
                it.onVideoFrameReceived(processedFrame)
            }
        }
        processedFrame?.release()
    }

    override fun addVideoSink(sink: VideoSink) {
        handler.post {
            sinks.add(sink)
        }
    }

    override fun removeVideoSink(sink: VideoSink) {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            sinks.remove(sink)
        }
    }

    override fun addCaptureSourceObserver(observer: CaptureSourceObserver) {
        observers.add(observer)
    }

    override fun removeCaptureSourceObserver(observer: CaptureSourceObserver) {
        observers.remove(observer)
    }

    fun dispose() {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger.info(TAG, "Stopping handler looper")
            handler.removeCallbacksAndMessages(null)
            handler.looper.quit()
        }
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
    ): DefaultVideoFrameTextureBuffer {
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
        return buffer.applyTransformMatrix(transformMatrix, buffer.width, buffer.height)
    }
}