package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.*
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.DefaultEglCore
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCore
import com.amazonaws.services.chime.sdk.meetings.device.MediaDevice
import com.amazonaws.services.chime.sdk.meetings.device.MediaDeviceType
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.xodee.client.video.JniUtil
import com.xodee.client.video.TimestampAligner
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.FloatBuffer


class DefaultCameraCaptureSource(
    private val context: Context,
    private val logger: Logger,
    private val sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : CameraCaptureSource, VideoSink {
    /**
     * Helper class for handling OpenGL framebuffer with only color attachment and no depth or stencil
     * buffer. Intended for simple tasks such as texture copy, texture downscaling, and texture color
     * conversion. This class is not thread safe and must be used by a thread with an active GL context.
     */
    private class GlFrameBufferHelper(pixelFormat: Int) {
        private var pixelFormat = 0

        /** Gets the OpenGL frame buffer id. This value is only valid after setSize() has been called.  */
        var frameBufferId = 0
            private set

        /** Gets the OpenGL texture id. This value is only valid after setSize() has been called.  */
        var textureId = 0
            private set
        var width: Int
            private set
        var height: Int
            private set

        /**
         * (Re)allocate texture. Will do nothing if the requested size equals the current size. An
         * EGLContext must be bound on the current thread when calling this function. Must be called at
         * least once before using the framebuffer. May be called multiple times to change size.
         */
        fun setSize(width: Int, height: Int) {
            require(!(width <= 0 || height <= 0)) { "Invalid size: " + width + "x" + height }
            if (width == this.width && height == this.height) {
                return
            }
            this.width = width
            this.height = height
            // Lazy allocation the first time setSize() is called.
            if (textureId == 0) {
                textureId =
                    DefaultEglCore.generateTexture(
                        GLES20.GL_TEXTURE_2D
                    )
            }
            if (frameBufferId == 0) {
                val frameBuffers = IntArray(1)
                GLES20.glGenFramebuffers(1, frameBuffers, 0)
                frameBufferId = frameBuffers[0]
            }

            // Allocate texture.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, pixelFormat, width, height, 0, pixelFormat,
                GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            DefaultEglCore.checkGlError("GlTextureFrameBuffer setSize")

            // Attach the texture to the framebuffer as color attachment.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0
            )

            // Check that the framebuffer is in a good state.
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer not complete, status: $status" }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        /**
         * Release texture and framebuffer. An EGLContext must be bound on the current thread when calling
         * this function. This object should not be used after this call.
         */
        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
            GLES20.glDeleteFramebuffers(1, intArrayOf(frameBufferId), 0)
            frameBufferId = 0
            width = 0
            height = 0
        }

        /**
         * Generate texture and framebuffer resources. An EGLContext must be bound on the current thread
         * when calling this function. The framebuffer is not complete until setSize() is called.
         */
        init {
            when (pixelFormat) {
                GLES20.GL_LUMINANCE, GLES20.GL_RGB, GLES20.GL_RGBA -> this.pixelFormat = pixelFormat
                else -> throw IllegalArgumentException("Invalid pixel format: $pixelFormat")
            }
            width = 0
            height = 0
        }
    }

    private class SurfaceTextureCaptureSource(
        private val logger: Logger,
        private val handler: Handler,
        private val sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
    ) : VideoSource {
        private var textureId: Int = 0;
        private lateinit var surfaceTexture: SurfaceTexture
        lateinit var surface: Surface
        private lateinit var eglCore: DefaultEglCore

        private val timestampAligner = TimestampAligner()
        var currentVideoCaptureFormat: VideoCaptureFormat? = null

        // Frame available listener was called when a texture was already in use
        private var pendingTexture = false

        // Texture is in use, possibly in another thread
        private var textureInUse = false

        // Dispose has been called and we are waiting on texture to be released
        private var quitting = false

        private var sinks = mutableSetOf<VideoSink>()

        override val contentHint: ContentHint = ContentHint.None

        private val TAG = "SurfaceTextureCaptureSource"

        init {
            runBlocking(handler.asCoroutineDispatcher().immediate) {
                eglCore =
                    DefaultEglCore(
                        sharedEGLContext,
                        logger = logger
                    )
                eglCore.createDummyPbufferSurface()
                eglCore.makeCurrent()
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                DefaultEglCore.checkGlError("Generating texture for video source")

                textureId = textures[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR
                );
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR
                );
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE
                );
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE
                );
                DefaultEglCore.checkGlError("Binding texture for video source")

                surfaceTexture = SurfaceTexture(textureId)
                surfaceTexture.setOnFrameAvailableListener({
                    logger.error(TAG, "Frame available")
                    pendingTexture = true
                    tryCapturingFrame()
                }, handler)
                @SuppressLint("Recycle")
                surface = Surface(surfaceTexture)

                logger.info(TAG, "Created surface texture for video source")
            }
        }

        fun start(format: VideoCaptureFormat) {
            runBlocking(handler.asCoroutineDispatcher().immediate) {
                quitting = false
                currentVideoCaptureFormat = format
                logger.info(
                    TAG,
                    "Setting surface texture buffer size for video source to ${format.width}x${format.height}"
                )
                currentVideoCaptureFormat?.let { surfaceTexture.setDefaultBufferSize(it.width, it.height) }
            }
        }

        fun stop() {
            runBlocking(handler.asCoroutineDispatcher().immediate) {
                logger.error(TAG, "Setting on frame available listener to null")
                surfaceTexture.setOnFrameAvailableListener(null)
            }
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

        fun release() {
            runBlocking(handler.asCoroutineDispatcher().immediate) {
                logger.info(TAG, "Releasing surface texture capture source")
                quitting = true
                if (!textureInUse) {
                    dispose();
                }
            }
        }

        private fun dispose() {
            logger.info(TAG, "Disposing surface texture capture source")
            runBlocking(handler.asCoroutineDispatcher().immediate) {
                surface.release()
                surfaceTexture.release()
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0);
                eglCore.release()

                timestampAligner.dispose()
            }
        }

        private fun frameReleased() {
            handler.post {
                logger.info(TAG, "Current frame released")
                textureInUse = false
                if (quitting) {
                    this.dispose()
                } else {
                    // May have pending frame
                    tryCapturingFrame()
                }
            }
        }

        private fun tryCapturingFrame() {
            logger.info(TAG, "q: $quitting, p: $pendingTexture, u: $textureInUse")

            if (quitting || !pendingTexture || textureInUse) {
                return;
            }
            textureInUse = true
            pendingTexture = false

            // This call is what actually updates the texture
            surfaceTexture.updateTexImage()

            val transformMatrix = FloatArray(16)
            surfaceTexture.getTransformMatrix(transformMatrix)

            val format = currentVideoCaptureFormat ?: return
            val buffer =
                DefaultVideoFrameTextureBuffer(
                    logger, format.width, format.height,
                    textureId, convertMatrixToAndroidGraphicsMatrix(transformMatrix),
                    VideoFrameTextureBuffer.Type.OES, Runnable { frameReleased() }, handler
                )
            val timestamp = timestampAligner.translateTimestamp(surfaceTexture.timestamp)
            val frame = VideoFrame(timestamp, buffer)

            sinks.forEach { it.onVideoFrameReceived(frame) }
            frame.release()
        }

        private fun convertMatrixToAndroidGraphicsMatrix(transformMatrix: FloatArray): Matrix {
            val values = floatArrayOf(
                transformMatrix[0 * 4 + 0], transformMatrix[1 * 4 + 0], transformMatrix[3 * 4 + 0],
                transformMatrix[0 * 4 + 1], transformMatrix[1 * 4 + 1], transformMatrix[3 * 4 + 1],
                transformMatrix[0 * 4 + 3], transformMatrix[1 * 4 + 3], transformMatrix[3 * 4 + 3]
            )
            val matrix = Matrix()
            matrix.setValues(values)
            return matrix
        }
    }

    /**
     * Class for converting OES textures to a YUV ByteBuffer. It can be constructed on any thread, but
     * should only be operated from a single thread with an active EGL context.
     */
    private class YuvConverter() {
        private val VERTEX_SHADER =
            """
varying vec2 tc;
attribute vec4 in_pos;
attribute vec4 in_tc;
uniform mat4 tex_mat;
void main() {
    gl_Position = in_pos;
    tc = (tex_mat * in_tc).xy;
}
"""

        private val FRAGMENT_SHADER_OES: String =
            """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 tc;
uniform samplerExternalOES tex;

uniform vec2 xUnit;
uniform vec4 coeffs;

void main() {
  gl_FragColor.r = coeffs.a + dot(coeffs.rgb,
      texture2D(tex, tc - 1.5 * xUnit).rgb);
  gl_FragColor.g = coeffs.a + dot(coeffs.rgb,
      texture2D(tex, tc - 0.5 * xUnit).rgb);
  gl_FragColor.b = coeffs.a + dot(coeffs.rgb,
      texture2D(tex, tc + 0.5 * xUnit).rgb);
  gl_FragColor.a = coeffs.a + dot(coeffs.rgb,
      texture2D(tex, tc + 1.5 * xUnit).rgb);
}
"""


        // Vertex coordinates in Normalized Device Coordinates, i.e. (-1, -1) is bottom-left and (1, 1)
// is top-right.
        private val FULL_RECTANGLE_BUFFER: FloatBuffer =
            DefaultEglCore.createFloatBuffer(
                floatArrayOf(
                    -1.0f, -1.0f,  // Bottom left.
                    1.0f, -1.0f,  // Bottom right.
                    -1.0f, 1.0f,  // Top left.
                    1.0f, 1.0f
                )
            )

        // Texture coordinates - (0, 0) is bottom-left and (1, 1) is top-right.
        private val FULL_RECTANGLE_TEXTURE_BUFFER: FloatBuffer =
            DefaultEglCore.createFloatBuffer(
                floatArrayOf(
                    0.0f, 0.0f,  // Bottom left.
                    1.0f, 0.0f,  // Bottom right.
                    0.0f, 1.0f,  // Top left.
                    1.0f, 1.0f
                )
            )

        private val INPUT_VERTEX_COORDINATE_NAME = "in_pos"
        private val INPUT_TEXTURE_COORDINATE_NAME = "in_tc"
        private val TEXTURE_MATRIX_NAME = "tex_mat"

        private var program: Int = DefaultEglCore.createProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)

        private var inPosLocation = 0
        private var inTcLocation = 0
        private var texMatrixLocation = 0

        private var xUnitLoc = 0
        private var coeffsLoc = 0
        private lateinit var coeffs: FloatArray
        private var stepSize = 0f

        private val yCoeffs =
            floatArrayOf(0.256788f, 0.504129f, 0.0979059f, 0.0627451f)
        private val uCoeffs =
            floatArrayOf(-0.148223f, -0.290993f, 0.439216f, 0.501961f)
        private val vCoeffs =
            floatArrayOf(0.439216f, -0.367788f, -0.0714274f, 0.501961f)


        private val i420TextureFrameBuffer =
            GlFrameBufferHelper(
                GLES20.GL_RGBA
            )

        fun convert(inputTextureBuffer: VideoFrameTextureBuffer): VideoFrameI420Buffer {
            // We use the same technique as the WebRTC SDK to draw into a buffer laid out like
            //
            //    +---------+
            //    |         |
            //    |  Y      |
            //    |         |
            //    |         |
            //    +----+----+
            //    | U  | V  |
            //    |    |    |
            //    +----+----+
            //
            // In memory, we use the same stride for all of Y, U and V. The
            // U data starts at offset |height| * |stride| from the Y data,
            // and the V data starts at at offset |stride/2| from the U
            // data, with rows of U and V data alternating.
            //
            // Now, it would have made sense to allocate a pixel buffer with
            // a single byte per pixel (EGL10.EGL_COLOR_BUFFER_TYPE,
            // EGL10.EGL_LUMINANCE_BUFFER,), but that seems to be
            // unsupported by devices. So do the following hack: Allocate an
            // RGBA buffer, of width |stride|/4. To render each of these
            // large pixels, sample the texture at 4 different x coordinates
            // and store the results in the four components.
            //
            // Since the V data needs to start on a boundary of such a
            // larger pixel, stride has to be a multiple of 8 pixels.
            val frameWidth: Int = inputTextureBuffer.width
            val frameHeight: Int = inputTextureBuffer.height


            val stride = (frameWidth + 7) / 8 * 8
            val uvHeight = (frameHeight + 1) / 2
            // Total height of the combined memory layout.
            val totalHeight = frameHeight + uvHeight

            val i420ByteBuffer: ByteBuffer = JniUtil.nativeAllocateByteBuffer(stride * totalHeight)

            // Viewport width is divided by four since we are squeezing in four color bytes in each RGBA
            // pixel.
            val viewportWidth = stride / 4

            // Produce a frame buffer starting at top-left corner, not bottom-left.
            val renderMatrix = Matrix()
            renderMatrix.preTranslate(0.5f, 0.5f)
            renderMatrix.preScale(1f, -1f)
            renderMatrix.preTranslate(-0.5f, -0.5f)

            i420TextureFrameBuffer.setSize(viewportWidth, totalHeight)

            // Bind our framebuffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, i420TextureFrameBuffer.frameBufferId)
            DefaultEglCore.checkGlError("glBindFramebuffer")

            // Draw Y.
            coeffs = yCoeffs
            stepSize = 1.0f
            val finalMatrix =
                Matrix(inputTextureBuffer.transformMatrix)
            finalMatrix.preConcat(renderMatrix)
            val finalGlMatrix: FloatArray =
                DefaultEglCore.convertMatrixFromAndroidGraphicsMatrix(
                    finalMatrix
                )

            prepareShader(
                finalGlMatrix,
                frameWidth,
                frameHeight,  /* viewportX= */
                viewportWidth,  /* viewportHeight= */
                frameHeight
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                inputTextureBuffer.textureId
            )
            DefaultEglCore.checkGlError("glBindTexture")
            GLES20.glViewport(
                0,
                /* viewportY= */
                0,
                viewportWidth, frameHeight
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            DefaultEglCore.checkGlError("glDrawArrays")


            // Draw U.
            coeffs = uCoeffs
            stepSize = 2.0f
            val viewportWidth1 = viewportWidth / 2  /* viewportHeight= */


            prepareShader(
                finalGlMatrix,
                frameWidth,
                frameHeight,  /* viewportX= */
                viewportWidth1,
                uvHeight
            )

            DefaultEglCore.checkGlError("glBindTexture")
            GLES20.glViewport(
                0,
                /* viewportY= */
                frameHeight,
                viewportWidth1, uvHeight
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            DefaultEglCore.checkGlError("glDrawArrays")


            // Draw V.
            coeffs = vCoeffs
            stepSize = 2.0f
            val viewportX = viewportWidth / 2  /* viewportY= */
            val viewportWidth2 = viewportWidth / 2  /* viewportHeight= */

            prepareShader(
                finalGlMatrix, frameWidth,
                frameHeight,  /* viewportX= */
                viewportWidth2, uvHeight
            )

            DefaultEglCore.checkGlError("glBindTexture")
            GLES20.glViewport(
                viewportX,
                frameHeight,
                viewportWidth2, uvHeight
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            DefaultEglCore.checkGlError("glDrawArrays")
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

            GLES20.glReadPixels(
                0, 0, i420TextureFrameBuffer.width, i420TextureFrameBuffer.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, i420ByteBuffer
            )

            DefaultEglCore.checkGlError("YuvConverter.convert")

            // Restore normal framebuffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            i420TextureFrameBuffer.release()

            // Prepare Y, U, and V ByteBuffer slices.
            val yPos = 0
            val uPos = yPos + stride * frameHeight
            // Rows of U and V alternate in the buffer, so V data starts after the first row of U.
            val vPos = uPos + stride / 2

            i420ByteBuffer.position(yPos)
            i420ByteBuffer.limit(yPos + stride * frameHeight)
            val dataY: ByteBuffer = i420ByteBuffer.slice()

            i420ByteBuffer.position(uPos)
            // The last row does not have padding.
            val uvSize = stride * (uvHeight - 1) + stride / 2
            i420ByteBuffer.limit(uPos + uvSize)
            val dataU: ByteBuffer = i420ByteBuffer.slice()

            i420ByteBuffer.position(vPos)
            i420ByteBuffer.limit(vPos + uvSize)
            val dataV: ByteBuffer = i420ByteBuffer.slice()

            return DefaultVideoFrameI420Buffer(
                frameWidth,
                frameHeight,
                dataY,
                dataU,
                dataV,
                stride,
                stride,
                stride,
                kotlinx.coroutines.Runnable {
                    JniUtil.nativeFreeByteBuffer(i420ByteBuffer)
                }
            )
        }

        private fun prepareShader(
            texMatrix: FloatArray?, frameWidth: Int,
            frameHeight: Int, viewportWidth: Int, viewportHeight: Int
        ) {

            GLES20.glUseProgram(program)
            DefaultEglCore.checkGlError("glUseProgram")

            val location = GLES20.glGetUniformLocation(program, "tex")

            GLES20.glUniform1i(location, 0)

            texMatrixLocation = GLES20.glGetUniformLocation(program, TEXTURE_MATRIX_NAME)
            inPosLocation = GLES20.glGetAttribLocation(program, INPUT_VERTEX_COORDINATE_NAME)
            inTcLocation = GLES20.glGetAttribLocation(program, INPUT_TEXTURE_COORDINATE_NAME)

            xUnitLoc = GLES20.glGetUniformLocation(program, "xUnit")
            coeffsLoc = GLES20.glGetUniformLocation(program, "coeffs")

            // Upload the vertex coordinates.
            GLES20.glEnableVertexAttribArray(inPosLocation)
            GLES20.glVertexAttribPointer(
                inPosLocation,  /* size= */2,  /* type= */
                GLES20.GL_FLOAT,  /* normalized= */false,  /* stride= */0,
                FULL_RECTANGLE_BUFFER
            )

            // Upload the texture coordinates.
            GLES20.glEnableVertexAttribArray(inTcLocation)
            GLES20.glVertexAttribPointer(
                inTcLocation,  /* size= */2,  /* type= */
                GLES20.GL_FLOAT,  /* normalized= */false,  /* stride= */0,
                FULL_RECTANGLE_TEXTURE_BUFFER
            )

            // Upload the texture transformation matrix.
            GLES20.glUniformMatrix4fv(
                texMatrixLocation,
                1 /* count= */,
                false /* transpose= */,
                texMatrix,
                0 /* offset= */
            )

            // Do custom per-frame shader preparation.
            GLES20.glUniform4fv(coeffsLoc,  /* count= */1, coeffs,  /* offset= */0)
            GLES20.glUniform2f(
                xUnitLoc,
                stepSize * texMatrix!![0] / frameWidth,
                stepSize * texMatrix[1] / frameWidth
            )
        }

        fun release() {
            i420TextureFrameBuffer.release()

            // Delete program, automatically detaching any shaders from it.
            if (program != -1) {
                GLES20.glDeleteProgram(program)
                program = -1
            }
        }

    }

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
        if (true) {//sharedEGLContext == EGL14.EGL_NO_CONTEXT) {
            logger.info(TAG, "No shared EGL context provided, will convert all frames to CPU memory")
            convertToCPU = true
        }
        thread.start()
        handler = Handler(thread.looper)

        runBlocking(handler.asCoroutineDispatcher().immediate) {
            eglCore =
                DefaultEglCore(
                    sharedEGLContext,
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
        TODO()
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
        var processedBuffer: VideoFrameBuffer =
            createTextureBufferWithModifiedTransformMatrix(frame.buffer as DefaultVideoFrameTextureBuffer, !isCameraFrontFacing, -cameraOrientation)

        if (convertToCPU) {
            processedBuffer.release()
            processedBuffer = YuvConverter().convert(processedBuffer as VideoFrameTextureBuffer)
        }
        val processedFrame =
            VideoFrame(
                frame.timestamp,
                processedBuffer,
                getFrameOrientation()
            )
        sinks.forEach{
                it.onVideoFrameReceived(processedFrame)
        }

        processedBuffer.release()
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