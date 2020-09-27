package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl

import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoFrame

/**
 * [GlVideoFrameDrawer] is an interface for OpenGL based texture drawing.  Currently all subclasses must upload
 * host memory buffers (i.e. VideoFrameI420Buffer) if support of those buffers is desired
 */
interface GlVideoFrameDrawer {
    fun drawVideoFrame(frame: VideoFrame)
}