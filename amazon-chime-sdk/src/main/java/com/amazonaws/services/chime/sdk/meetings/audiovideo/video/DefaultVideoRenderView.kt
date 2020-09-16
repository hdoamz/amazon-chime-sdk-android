/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.content.Context
import android.content.res.Resources.NotFoundException
import android.graphics.Point
import android.opengl.EGL14
import android.opengl.EGLContext
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.*
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class DefaultVideoRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle), SurfaceHolder.Callback, VideoRenderView {
    // Accessed only on the main thread.
    private var rotatedFrameWidth = 0
    private var rotatedFrameHeight = 0
    private var frameRotation = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private lateinit var eglCore: EglCore
    private val eglRenderer: EglRenderer = EglRenderer(getResourceName())
    private val videoLayoutMeasure: RendererCommon.VideoLayoutMeasure =
        RendererCommon.VideoLayoutMeasure()
    private lateinit var logger: Logger
    private val TAG = "DefaultVideoRenderView"

    init {
        holder.addCallback(this)
        videoLayoutMeasure.setScalingType(ScalingType.SCALE_ASPECT_FIT)
    }

    private fun getResourceName(): String? {
        return try {
            resources.getResourceEntryName(id)
        } catch (e: NotFoundException) {
            ""
        }
    }

    fun init(logger: Logger, eglContext: EGLContext = EGL14.EGL_NO_CONTEXT) {
        this.logger = logger
        this.logger.info(TAG, "Initialized by application")

        rotatedFrameWidth = 0;
        rotatedFrameHeight = 0;
        eglRenderer.init(eglContext, GlRectDrawer(), logger);

    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        logger.info(TAG, "Surface changed: format:$format, dimensions:${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        logger.info(TAG, "Surface destroyed")
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        logger.info(TAG, "Surface created")

        surfaceWidth = 0;
        surfaceHeight = 0;
        updateSurfaceSize();

        holder?.let {
            eglRenderer.createEglSurface(it.surface)
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
        eglRenderer.setLayoutAspectRatio((right - left) / (bottom - top).toFloat())
        updateSurfaceSize()
    }

    override fun onFrameCaptured(frame: VideoFrame) {

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

        eglRenderer.render(frame)
        frame.release()
        logger.info(TAG, "Frame captured")
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
}
