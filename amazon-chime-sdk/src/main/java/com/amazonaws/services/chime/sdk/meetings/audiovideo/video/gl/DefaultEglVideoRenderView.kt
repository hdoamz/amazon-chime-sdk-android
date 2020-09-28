/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import android.content.Context
import android.graphics.Matrix
import android.graphics.Point
import android.opengl.EGLContext
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameI420Buffer
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrameTextureBuffer
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.xodee.client.video.YuvUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.FloatBuffer


open class DefaultEglVideoRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle), SurfaceHolder.Callback,
    EglVideoRenderView {
    /**
     * Helper class for determining layout size based on layout requirements, scaling type, and video
     * aspect ratio.
     */
    class VideoLayoutMeasure {
        // Types of video scaling:
// SCALE_ASPECT_FIT - video frame is scaled to fit the size of the view by
//    maintaining the aspect ratio (black borders may be displayed).
// SCALE_ASPECT_FILL - video frame is scaled to fill the size of the view by
//    maintaining the aspect ratio. Some portion of the video frame may be
//    clipped.
// SCALE_ASPECT_BALANCED - Compromise between FIT and FILL. Video frame will fill as much as
// possible of the view while maintaining aspect ratio, under the constraint that at least
// |BALANCED_VISIBLE_FRACTION| of the frame content will be shown.
        enum class ScalingType {
            SCALE_ASPECT_FIT, SCALE_ASPECT_FILL, SCALE_ASPECT_BALANCED
        }

        // The minimum fraction of the frame content that will be shown for |SCALE_ASPECT_BALANCED|.
// This limits excessive cropping when adjusting display size.
        private val BALANCED_VISIBLE_FRACTION = 0.5625f


        // The scaling type determines how the video will fill the allowed layout area in measure(). It
        // can be specified separately for the case when video has matched orientation with layout size
        // and when there is an orientation mismatch.
        private var visibleFractionMatchOrientation: Float =
            convertScalingTypeToVisibleFraction(
                ScalingType.SCALE_ASPECT_BALANCED
            )
        private var visibleFractionMismatchOrientation: Float =
            convertScalingTypeToVisibleFraction(
                ScalingType.SCALE_ASPECT_BALANCED
            )

        fun setScalingType(scalingType: ScalingType?) {
            setScalingType( /* scalingTypeMatchOrientation= */scalingType,  /* scalingTypeMismatchOrientation= */
                scalingType
            )
        }

        private fun setScalingType(
            scalingTypeMatchOrientation: ScalingType?, scalingTypeMismatchOrientation: ScalingType?
        ) {
            visibleFractionMatchOrientation =
                scalingTypeMatchOrientation?.let {
                    convertScalingTypeToVisibleFraction(
                        it
                    )
                }!!
            visibleFractionMismatchOrientation =
                scalingTypeMismatchOrientation?.let {
                    convertScalingTypeToVisibleFraction(
                        it
                    )
                }!!
        }

        fun measure(
            widthSpec: Int,
            heightSpec: Int,
            frameWidth: Int,
            frameHeight: Int
        ): Point {
            // Calculate max allowed layout size.
            val maxWidth = View.getDefaultSize(Int.MAX_VALUE, widthSpec)
            val maxHeight =
                View.getDefaultSize(Int.MAX_VALUE, heightSpec)
            if (frameWidth == 0 || frameHeight == 0 || maxWidth == 0 || maxHeight == 0) {
                return Point(maxWidth, maxHeight)
            }
            // Calculate desired display size based on scaling type, video aspect ratio,
            // and maximum layout size.
            val frameAspect = frameWidth / frameHeight.toFloat()
            val displayAspect = maxWidth / maxHeight.toFloat()
            val visibleFraction =
                if (frameAspect > 1.0f == displayAspect > 1.0f) visibleFractionMatchOrientation else visibleFractionMismatchOrientation
            val layoutSize: Point =
                getDisplaySize(
                    visibleFraction,
                    frameAspect,
                    maxWidth,
                    maxHeight
                )

            // If the measure specification is forcing a specific size - yield.
            if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.EXACTLY) {
                layoutSize.x = maxWidth
            }
            if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.EXACTLY) {
                layoutSize.y = maxHeight
            }
            return layoutSize
        }

        /**
         * Each scaling type has a one-to-one correspondence to a numeric minimum fraction of the video
         * that must remain visible.
         */
        private fun convertScalingTypeToVisibleFraction(scalingType: ScalingType): Float {
            return when (scalingType) {
                ScalingType.SCALE_ASPECT_FIT -> 1.0f
                ScalingType.SCALE_ASPECT_FILL -> 0.0f
                ScalingType.SCALE_ASPECT_BALANCED -> BALANCED_VISIBLE_FRACTION
                else -> throw IllegalArgumentException()
            }
        }

        /**
         * Calculate display size based on minimum fraction of the video that must remain visible,
         * video aspect ratio, and maximum display size.
         */
        private fun getDisplaySize(
            minVisibleFraction: Float,
            videoAspectRatio: Float,
            maxDisplayWidth: Int,
            maxDisplayHeight: Int
        ): Point {
            // If there is no constraint on the amount of cropping, fill the allowed display area.
            if (minVisibleFraction == 0f || videoAspectRatio == 0f) {
                return Point(maxDisplayWidth, maxDisplayHeight)
            }
            // Each dimension is constrained on max display size and how much we are allowed to crop.
            val width = Math.min(
                maxDisplayWidth,
                Math.round(maxDisplayHeight / minVisibleFraction * videoAspectRatio)
            )
            val height = Math.min(
                maxDisplayHeight,
                Math.round(maxDisplayWidth / minVisibleFraction / videoAspectRatio)
            )
            return Point(width, height)
        }
    }

    /**
     * [DefaultGlVideoFrameDrawer] simply draws the frames as opaque quads onto the current surface
     */
    class DefaultGlVideoFrameDrawer {
        enum class ShaderType {
            OES, RGB, YUV
        }

        val TAG = "VideoFrameDrawer"

        private val INPUT_VERTEX_COORDINATE_NAME = "in_pos"
        private val INPUT_TEXTURE_COORDINATE_NAME = "in_tc"
        private val TEXTURE_MATRIX_NAME = "tex_mat"

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

void main() {
  gl_FragColor = texture2D(tex, tc);
}
"""

        private val FRAGMENT_SHADER_RGB: String =
            """
precision mediump float;
varying vec2 tc;
uniform samplerExternalOES tex;

void main() {
  gl_FragColor = texture2D(tex, tc);
}
"""

        private val FRAGMENT_SHADER_YUV: String =
            """
precision mediump float;

varying vec2 tc;

uniform sampler2D y_tex;
uniform sampler2D u_tex;
uniform sampler2D v_tex;

vec4 sample(vec2 p) {
  float y = texture2D(y_tex, p).r * 1.16438;
  float u = texture2D(u_tex, p).r;
  float v = texture2D(v_tex, p).r;
  return vec4(y + 1.59603 * v - 0.874202,
    y - 0.391762 * u - 0.812968 * v + 0.531668,
    y + 2.01723 * u - 1.08563, 1);
}

void main() {
  gl_FragColor = sample(tc);
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


        private fun distance(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float
        ): Int {
            return Math.round(
                Math.hypot(
                    x1 - x0.toDouble(),
                    y1 - y0.toDouble()
                )
            ).toInt()
        }

        // These points are used to calculate the size of the part of the frame we are rendering.
        val srcPoints = floatArrayOf(
            0f /* x0 */,
            0f /* y0 */,
            1f /* x1 */,
            0f /* y1 */,
            0f /* x2 */,
            1f /* y2 */
        )


        private var currentShaderType: ShaderType? = null

        private var currentShader: GlShader? =
            null
        private var inPosLocation = 0
        private var inTcLocation = 0
        private var texMatrixLocation = 0

        /**
         * Helper class for uploading YUV bytebuffer frames to textures that handles stride > width. This
         * class keeps an internal ByteBuffer to avoid unnecessary allocations for intermediate copies.
         */
        private class YuvUploader {
            // Intermediate copy buffer for uploading yuv frames that are not packed, i.e. stride > width.
            // TODO(magjed): Investigate when GL_UNPACK_ROW_LENGTH is available, or make a custom shader
            // that handles stride and compare performance with intermediate copy.
            private var copyBuffer: ByteBuffer? = null

            var yuvTextures: IntArray? = null

            /**
             * Upload |planes| into OpenGL textures, taking stride into consideration.
             *
             * @return Array of three texture indices corresponding to Y-, U-, and V-plane respectively.
             */
            fun uploadYuvData(
                width: Int,
                height: Int,
                strides: IntArray,
                planes: Array<ByteBuffer?>
            ): IntArray {
                val planeWidths = intArrayOf(width, width / 2, width / 2)
                val planeHeights = intArrayOf(height, height / 2, height / 2)
                // Make a first pass to see if we need a temporary copy buffer.
                var copyCapacityNeeded = 0
                for (i in 0..2) {
                    if (strides[i] > planeWidths[i]) {
                        copyCapacityNeeded = Math.max(
                            copyCapacityNeeded,
                            planeWidths[i] * planeHeights[i]
                        )
                    }
                }
                // Allocate copy buffer if necessary.
                if (copyCapacityNeeded > 0
                    && (copyBuffer == null || copyBuffer!!.capacity() < copyCapacityNeeded)
                ) {
                    Log.e("test", "allocating")
                    copyBuffer = ByteBuffer.allocateDirect(copyCapacityNeeded)
                }
                // Make sure YUV textures are allocated.
                if (yuvTextures == null) {
                    yuvTextures = IntArray(3)
                    for (i in 0..2) {
                        yuvTextures!![i] =
                            DefaultEglCore.generateTexture(
                                GLES20.GL_TEXTURE_2D
                            )
                    }
                }
                // Upload each plane.
                for (i in 0..2) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures!![i])
                    // GLES only accepts packed data, i.e. stride == planeWidth.
                    val packedByteBuffer: ByteBuffer?
                    packedByteBuffer = if (strides[i] == planeWidths[i]) {
                        // Input is packed already.
                        planes[i]
                    } else {
                        YuvUtil.copyPlane(
                            planes[i],
                            strides[i],
                            copyBuffer,
                            planeWidths[i],
                            planeWidths[i],
                            planeHeights[i]
                        )
                        copyBuffer
                    }
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_LUMINANCE,
                        planeWidths[i],
                        planeHeights[i],
                        0,
                        GLES20.GL_LUMINANCE,
                        GLES20.GL_UNSIGNED_BYTE,
                        packedByteBuffer
                    )
                }
                return yuvTextures!!
            }

            fun uploadFromBuffer(buffer: VideoFrameI420Buffer): IntArray {
                val strides =
                    intArrayOf(buffer.strideY, buffer.strideU, buffer.strideV)
                val planes = arrayOf<ByteBuffer?>(
                    buffer.dataY,
                    buffer.dataU,
                    buffer.dataV
                )
                return uploadYuvData(buffer.width, buffer.height, strides, planes)
            }

            /**
             * Releases cached resources. Uploader can still be used and the resources will be reallocated
             * on first use.
             */
            fun release() {
                copyBuffer = null
                if (yuvTextures != null) {
                    GLES20.glDeleteTextures(3, yuvTextures, 0)
                    yuvTextures = null
                }
            }
        }

        private val dstPoints = FloatArray(6)
        private var renderWidth = 0
        private var renderHeight = 0

        // Calculate the frame size after |renderMatrix| is applied. Stores the output in member variables
        // |renderWidth| and |renderHeight| to avoid allocations since this function is called for every
        // frame.
        private fun calculateTransformedRenderSize(
            frameWidth: Int, frameHeight: Int, renderMatrix: Matrix?
        ) {
            if (renderMatrix == null) {
                renderWidth = frameWidth
                renderHeight = frameHeight
                return
            }
            // Transform the texture coordinates (in the range [0, 1]) according to |renderMatrix|.
            renderMatrix.mapPoints(dstPoints, srcPoints)

            // Multiply with the width and height to get the positions in terms of pixels.
            for (i in 0..2) {
                dstPoints[i * 2 + 0] = dstPoints[i * 2 + 0] * frameWidth
                dstPoints[i * 2 + 1] = dstPoints[i * 2 + 1] * frameHeight
            }

            // Get the length of the sides of the transformed rectangle in terms of pixels.
            renderWidth = distance(
                dstPoints[0],
                dstPoints[1],
                dstPoints[2],
                dstPoints[3]
            )
            renderHeight = distance(
                dstPoints[0],
                dstPoints[1],
                dstPoints[4],
                dstPoints[5]
            )
        }

        private val yuvUploader = YuvUploader()

        private val renderMatrix = Matrix()

        fun drawFrame(
            frame: VideoFrame,
            additionalRenderMatrix: Matrix?,
            viewportX: Int,
            viewportY: Int,
            viewportWidth: Int,
            viewportHeight: Int
        ) {
            val width: Int = frame.getRotatedWidth()
            val height: Int = frame.getRotatedHeight()
            calculateTransformedRenderSize(width, height, additionalRenderMatrix)
            if (renderWidth <= 0 || renderHeight <= 0) {
                return
            }
            val isTextureFrame = frame.buffer is VideoFrameTextureBuffer
            renderMatrix.reset()
            renderMatrix.preTranslate(0.5f, 0.5f)
            if (!isTextureFrame) {
                renderMatrix.preScale(1f, -1f) // I420-frames are upside down
            }
            renderMatrix.preRotate(frame.rotation.toFloat())
            renderMatrix.preTranslate(-0.5f, -0.5f)
            if (additionalRenderMatrix != null) {
                renderMatrix.preConcat(additionalRenderMatrix)
            }
            if (isTextureFrame) {
                Log.e("ASD", "TEXTUre")
                val textureBuffer = frame.buffer as VideoFrameTextureBuffer
                val finalMatrix =
                    Matrix(textureBuffer.transformMatrix)
                finalMatrix.preConcat(renderMatrix)
                val finalGlMatrix =
                    DefaultEglCore.convertMatrixFromAndroidGraphicsMatrix(
                        finalMatrix
                    )
                when (textureBuffer.type) {
                    VideoFrameTextureBuffer.Type.OES -> drawOes(
                        textureBuffer.textureId, finalGlMatrix, textureBuffer.width, textureBuffer.height, viewportX,
                        viewportY, viewportWidth, viewportHeight
                    )
                    VideoFrameTextureBuffer.Type.RGB -> drawRgb(
                        textureBuffer.textureId, finalGlMatrix, textureBuffer.width, textureBuffer.height, viewportX,
                        viewportY, viewportWidth, viewportHeight
                    )
                }
            } else if (frame.buffer is VideoFrameI420Buffer) {
                yuvUploader.uploadFromBuffer(frame.buffer)
                drawYuv(
                    yuvUploader.yuvTextures,
                    DefaultEglCore.convertMatrixFromAndroidGraphicsMatrix(
                        renderMatrix
                    ), renderWidth,
                    renderHeight, viewportX, viewportY, viewportWidth, viewportHeight
                )
            }
        }

        /**
         * Draw an OES texture frame with specified texture transformation matrix. Required resources are
         * allocated at the first call to this function.
         */
        fun drawOes(
            oesTextureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
            viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
        ) {
            prepareShader(
                ShaderType.OES, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
            )
            // Bind the texture.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            DefaultEglCore.checkGlError("glBindTexture")

            // Draw the texture.
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            DefaultEglCore.checkGlError("glDrawArrays")

            // Unbind the texture as a precaution.
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        /**
         * Draw a RGB(A) texture frame with specified texture transformation matrix. Required resources
         * are allocated at the first call to this function.
         */
        fun drawRgb(
            textureId: Int, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
            viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
        ) {
            prepareShader(
                ShaderType.RGB, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
            )
            // Bind the texture.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            // Draw the texture.
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // Unbind the texture as a precaution.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        /**
         * Draw a YUV frame with specified texture transformation matrix. Required resources are allocated
         * at the first call to this function.
         */
        fun drawYuv(
            yuvTextures: IntArray?, texMatrix: FloatArray?, frameWidth: Int, frameHeight: Int,
            viewportX: Int, viewportY: Int, viewportWidth: Int, viewportHeight: Int
        ) {
            prepareShader(
                ShaderType.YUV, texMatrix, frameWidth, frameHeight, viewportWidth, viewportHeight
            )
            // Bind the textures.
            for (i in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures!![i])
            }
            // Draw the textures.
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // Unbind the textures as a precaution.
            for (i in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            }
        }

        private fun prepareShader(
            shaderType: ShaderType, texMatrix: FloatArray?, frameWidth: Int,
            frameHeight: Int, viewportWidth: Int, viewportHeight: Int
        ) {
            val shader: GlShader?
            if (shaderType == currentShaderType) {
                // Same shader type as before, reuse exising shader.
                shader = currentShader
            } else {
                // Allocate new shader.
                currentShaderType = shaderType
                currentShader?.release()
                shader = GlShader(
                    VERTEX_SHADER,
                    FRAGMENT_SHADER_YUV
                )
                currentShader = shader
                shader.useProgram()

                // Set input texture units.
                if (shaderType == ShaderType.YUV) {
                    GLES20.glUniform1i(shader.getUniformLocation("y_tex"), 0)
                    GLES20.glUniform1i(shader.getUniformLocation("u_tex"), 1)
                    GLES20.glUniform1i(shader.getUniformLocation("v_tex"), 2)
                } else {
                    GLES20.glUniform1i(shader.getUniformLocation("tex"), 0)
                }

                texMatrixLocation =
                    shader.getUniformLocation(TEXTURE_MATRIX_NAME)
                inPosLocation =
                    shader.getAttribLocation(INPUT_VERTEX_COORDINATE_NAME)
                inTcLocation =
                    shader.getAttribLocation(INPUT_TEXTURE_COORDINATE_NAME)
            }
            shader!!.useProgram()

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
                texMatrixLocation, 1 /* count= */, false /* transpose= */, texMatrix, 0 /* offset= */
            )
        }

        fun release() {
            yuvUploader.release()

            if (currentShader != null) {
                currentShader!!.release()
                currentShader = null
                currentShaderType = null
            }
        }
    }

    // Accessed only on the main thread.
    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var frameRotation = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var renderHandler: Handler? = null

    // EGL and GL resources for drawing YUV/OES textures. After initialization, these are only
    // accessed from the render thread.
    private var eglCore: DefaultEglCore? = null
    private val surface: Any? = null

    private var usePresentationTimeStamp = false
    private val drawMatrix: Matrix = Matrix()

    // Pending frame to render. Serves as a queue with size 1. Synchronized on |frameLock|.
    private val frameLock = Any()

    private var pendingFrame: VideoFrame? = null

    // These variables are synchronized on |layoutLock|.
    private val layoutLock = Any()
    private var layoutAspectRatio = 0f

    // If true, mirrors the video stream horizontally.
    private val mirrorHorizontally = false

    // If true, mirrors the video stream vertically.
    private val mirrorVertically = false

    private val frameDrawer: DefaultGlVideoFrameDrawer = DefaultGlVideoFrameDrawer()

    private val videoLayoutMeasure: VideoLayoutMeasure =
        VideoLayoutMeasure()
    private lateinit var logger: Logger
    private val TAG = "DefaultVideoRenderView"

    init {
        holder.addCallback(this)
        videoLayoutMeasure.setScalingType(VideoLayoutMeasure.ScalingType.SCALE_ASPECT_FIT)
    }

    override fun init(logger: Logger, eglContext: EGLContext) {
        this.logger = logger
        this.logger.info(TAG, "Initialized")

        rotatedFrameWidth = 0;
        rotatedFrameHeight = 0;

        val thread = HandlerThread("SurfaceTextureVideoSource")
        thread.start()
        this.renderHandler = Handler(thread.looper)

        this.logger = logger

        val handler = this.renderHandler ?: throw UnknownError("No handler in init")
        // Create EGL context on the newly created render thread. It should be possibly to create the
        // context on this thread and make it current on the render thread, but this causes failure on
        // some Marvel based JB devices. https://bugs.chromium.org/p/webrtc/issues/detail?id=6350.
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            eglCore =
                DefaultEglCore(
                    eglContext,
                    logger = logger
                )
            logger?.info(TAG, "Renderer initialized")
            surface?.let { createEglSurfaceInternal(it) }
        }
        this.logger?.info(TAG, "Renderer initialized")

    }

    override fun dispose() {
        this.logger.info(TAG, "Releasing")
        releaseRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        releaseEglSurface();
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        surfaceWidth = 0;
        surfaceHeight = 0;
        updateSurfaceSize();

        holder?.let {
            createEglSurfaceInternal(it.surface)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size: Point =
            videoLayoutMeasure.measure(widthSpec, heightSpec, rotatedFrameWidth, rotatedFrameHeight)
        setMeasuredDimension(size.x, size.y)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        setLayoutAspectRatio((right - left) / (bottom - top).toFloat())
        updateSurfaceSize()
    }

    override fun onVideoFrameReceived(frame: VideoFrame) {
        if (rotatedFrameWidth != frame.getRotatedWidth()
            || rotatedFrameHeight != frame.getRotatedHeight()
            || frameRotation != frame.rotation) {
            logger.info(TAG,
                "Reporting frame resolution changed to ${frame.width}x${frame.height} with rotation ${frame.rotation}"
            )

            rotatedFrameWidth = frame.getRotatedWidth();
            rotatedFrameHeight = frame.getRotatedHeight();
            frameRotation = frame.rotation;

            CoroutineScope(Dispatchers.Main).launch {
                updateSurfaceSize();
                requestLayout();
            }
        }

        render(frame)
    }

    private fun updateSurfaceSize() {
        if (rotatedFrameWidth != 0 && rotatedFrameHeight != 0 && width != 0 && height != 0) {
            val layoutAspectRatio = width / height.toFloat()
            val frameAspectRatio: Float =
                rotatedFrameWidth.toFloat() / rotatedFrameHeight
            val drawnFrameWidth: Int
            val drawnFrameHeight: Int
            if (frameAspectRatio > layoutAspectRatio) {
                drawnFrameWidth = ((rotatedFrameHeight * layoutAspectRatio).toInt())
                drawnFrameHeight = rotatedFrameHeight
            } else {
                drawnFrameWidth = rotatedFrameWidth
                drawnFrameHeight = ((rotatedFrameWidth / layoutAspectRatio).toInt())
            }
            // Aspect ratio of the drawn frame and the view is the same.
            val width = Math.min(width, drawnFrameWidth)
            val height = Math.min(height, drawnFrameHeight)
            logger.info(
                TAG,
                "updateSurfaceSize. Layout size: " + getWidth() + "x" + getHeight() + ", frame size: "
                        + rotatedFrameWidth + "x" + rotatedFrameHeight + ", requested surface size: " + width
                        + "x" + height + ", old surface size: " + surfaceWidth + "x" + surfaceHeight
            )
            if (width != surfaceWidth || height != surfaceHeight) {
                surfaceWidth = width
                surfaceHeight = height
                holder.setFixedSize(width, height)
            }
        } else {
            surfaceHeight = 0
            surfaceWidth = surfaceHeight
            holder.setSizeFromLayout()
        }
    }

    fun createEglSurfaceInternal(surface: Any) {
        val handler = this.renderHandler ?: throw UnknownError("No handler in call to create EGL Surface")
        handler.post {
            if (eglCore != null && eglCore?.hasSurface() == false) {
                eglCore?.createWindowSurface(surface)
                eglCore?.makeCurrent()

                // Necessary for YUV frames with odd width.
                // GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                logger?.info(TAG, "Created window surface for EGLRenderer")

            }
        }
    }

    fun releaseEglSurface() {
        val handler = this.renderHandler ?: return
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL surface")

            eglCore?.makeNothingCurrent()
            eglCore?.releaseSurface()
        }
    }

    fun render(frame: VideoFrame) {
        synchronized(frameLock) {
            if (pendingFrame != null) {
                logger?.info(TAG, "Releasing pending frame")
                pendingFrame?.release()
            }
            pendingFrame = frame
            pendingFrame?.retain()
            val handler = renderHandler ?: throw UnknownError("No handler in render function")
            handler.post(::renderFrameOnRenderThread)
        }
    }

    fun setLayoutAspectRatio(layoutAspectRatio: Float) {
        synchronized(layoutLock) { this.layoutAspectRatio = layoutAspectRatio }
    }
    /**
     * Block until any pending frame is returned and all GL resources released, even if an interrupt
     * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
     * should be called before the Activity is destroyed and the EGLContext is still valid. If you
     * don't call this function, the GL resources might leak.
     */
    fun releaseRender() {
        val handler = renderHandler ?: throw UnknownError("No handler in release")
        runBlocking(handler.asCoroutineDispatcher().immediate) {
            logger?.info(TAG, "Releasing EGL resources")
            // Detach current shader program.
            GLES20.glUseProgram( /* program= */0)
            frameDrawer.release()

            eglCore?.makeNothingCurrent()
            eglCore?.release()
            eglCore = null
        }
        val renderLooper: Looper = handler.looper
        renderLooper.quitSafely()
        this.renderHandler = null

        synchronized (frameLock) {
            if (pendingFrame != null) {
                pendingFrame?.release();
                pendingFrame = null;
            }
        }
    }

    private fun renderFrameOnRenderThread() {
        // Fetch and render |pendingFrame|.
        var frame: VideoFrame
        synchronized(frameLock) {
            if (pendingFrame == null) {
                return
            }
            frame = pendingFrame as VideoFrame
            pendingFrame = null
        }

        val frameAspectRatio =
            frame.getRotatedWidth() / frame.getRotatedHeight().toFloat()
        var drawnAspectRatio: Float
        synchronized(
            layoutLock
        ) {
            drawnAspectRatio =
                if (layoutAspectRatio != 0f) layoutAspectRatio else frameAspectRatio
        }
        val scaleX: Float
        val scaleY: Float
        if (frameAspectRatio > drawnAspectRatio) {
            scaleX = drawnAspectRatio / frameAspectRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = frameAspectRatio / drawnAspectRatio
        }
        drawMatrix.reset()
        drawMatrix.preTranslate(0.5f, 0.5f)
        drawMatrix.preScale(if (mirrorHorizontally) -1f else 1f, if (mirrorVertically) -1f else 1f)
        drawMatrix.preScale(scaleX, scaleY)
        drawMatrix.preTranslate(-0.5f, -0.5f)
        GLES20.glClearColor(1f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        eglCore?.let {
            frameDrawer.drawFrame(
                frame, drawMatrix, 0 /* viewportX */, 0 /* viewportY */,
                it.surfaceWidth(), it.surfaceHeight()
            )
            val swapBuffersStartTimeNs = System.nanoTime()
            if (usePresentationTimeStamp) {
//                    it.swapBuffers(frame.getTimestampNs())
            } else {
                it.swapBuffers()
            }
        }
        frame.release()
    }
}
