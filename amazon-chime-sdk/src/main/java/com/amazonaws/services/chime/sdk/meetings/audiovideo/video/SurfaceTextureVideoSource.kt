package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

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
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.EglCore
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.GlUtil
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking


open class SurfaceTextureVideoSource(
    logger: Logger,
    private val sharedEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
) : VideoSource {
    private val thread: HandlerThread

    protected val handler: Handler
    protected lateinit var surface: Surface

    private var textureId: Int = 0;
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var eglCore: EglCore

    private var sinks = mutableSetOf<VideoSink>()

    private val TAG = "SurfaceTextureVideoSource"

    init {
        thread = HandlerThread("SurfaceTextureVideoSource")
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
            surfaceTexture.setOnFrameAvailableListener {
                handler.post {
                    logger.error(TAG, "Frame available")
                    it.updateTexImage()

                    val transformMatrix = FloatArray(16)
                    it.getTransformMatrix(transformMatrix)
                    val buffer = DefaultVideoFrameTextureBuffer(
                        960,
                        720,
                        textureId,
                        convertMatrixToAndroidGraphicsMatrix(transformMatrix),
                        handler
                    )
                    val frame = VideoFrame(960, 720, 0, buffer)
                    for (sink in sinks) {
                        sink.onFrameCaptured(frame)
                    }
                }
            }
            surfaceTexture.setDefaultBufferSize(960, 720)
            @SuppressLint("Recycle")
            surface = Surface(surfaceTexture)

            logger.info(TAG ,"Created surface texture for video source")
        }
    }

    override fun addSink(sink: VideoSink) {
        sinks.add(sink)
    }

    override fun removeSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    fun release() {
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0);
            eglCore.release()
            handler.looper.quit()
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
