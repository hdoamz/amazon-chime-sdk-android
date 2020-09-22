package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.CallSuper
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.*
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCore
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.GlUtil
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.xodee.client.video.TimestampAligner
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking


abstract class SurfaceTextureCaptureSource(
    private val logger: Logger,
    private val sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : VideoCaptureSource {
    private val thread: HandlerThread = HandlerThread("SurfaceTextureVideoSource")
    protected val handler: Handler

    private var textureId: Int = 0;
    private lateinit var surfaceTexture: SurfaceTexture
    protected lateinit var surface: Surface
    private lateinit var eglCore: EglCore

    private val timestampAligner = TimestampAligner()
    private var videoFrameProcessor: ((VideoFrame)->VideoFrame)? = null
    protected var currentVideoCaptureFormat: VideoCaptureFormat? = null

    // Frame available listener was called when a texture was already in use
    private var pendingTexture = false

    // Texture is in use, possibly in another thread
    private var textureInUse = false

    // Dispose has been called and we are waiting on texture to be released
    private var quitting = false

    private var sinks = mutableSetOf<VideoSink>()

    private val TAG = "SurfaceTextureCaptureSource"

    init {
        thread.start()
        handler = Handler(thread.looper)

        runBlocking(handler.asCoroutineDispatcher().immediate) {
            eglCore =
                EglCore(
                    sharedEGLContext,
                    logger = logger
                )
            eglCore.createDummyPbufferSurface()
            eglCore.makeCurrent()
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GlUtil.checkGlError("Generating texture for video source")

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
            GlUtil.checkGlError("Binding texture for video source")

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

    @CallSuper
    override fun start(format: VideoCaptureFormat) {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            currentVideoCaptureFormat = format
            logger.info(
                TAG,
                "Setting surface texture buffer size for video source to ${format.width}x${format.height}"
            )
            currentVideoCaptureFormat?.let { surfaceTexture.setDefaultBufferSize(it.width, it.height) }


        }
    }

    @CallSuper
    override fun stop() {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger.error(TAG, "Setting on frame available listener to null")
            //surfaceTexture.setOnFrameAvailableListener(null)
        }
    }

    override fun addVideoSink(sink: VideoSink) {
        sinks.add(sink)
    }

    override fun removeVideoSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    fun setFrameProcessor(videoFrameProcessor: ((VideoFrame)->VideoFrame)?) {
        this.videoFrameProcessor = videoFrameProcessor
    }

    fun dispose() {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            quitting = true
            if (!textureInUse) {
                release();
            }
        }
    }

    private fun release() {
        logger.info(TAG, "Releasing surface texture capture source")
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0);
            eglCore.release()

            logger.info(TAG, "Stopping handler looper")
            handler.removeCallbacksAndMessages(null)
            handler.looper.quit()
        }
    }

    private fun frameReleased() {
        handler.post {
            logger.info(TAG, "Current frame released")
            textureInUse = false
            if (quitting) {
                release()
            } else {
                // May have pending frame
                tryCapturingFrame()
            }
        }
    }

    private fun tryCapturingFrame() {
        if (quitting || !pendingTexture || textureInUse) {
            return;
        }
        logger.info(TAG, "tryCapturingFrame")

        textureInUse = true
        pendingTexture = false

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
        val originalFrame =
            VideoFrame(
                format.width, format.height,
                timestamp, buffer
            )

        val newFrame = if (videoFrameProcessor != null) {
            val processedFrame = videoFrameProcessor?.invoke(originalFrame)
            originalFrame.release()
            processedFrame
        } else {
            originalFrame
        }

        newFrame?.let {frame ->
            sinks.forEach {
                it.onVideoFrameReceived(frame)
            }
            frame.release()
        }
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
