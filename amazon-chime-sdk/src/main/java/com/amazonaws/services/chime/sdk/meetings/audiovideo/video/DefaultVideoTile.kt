/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger

class DefaultVideoTile(
    private val logger: Logger,
    tileId: Int,
    attendeeId: String,
    videoStreamContentWidth: Int,
    videoStreamContentHeight: Int,
    isLocalTile: Boolean
) : VideoTile {
    private val TAG = "DefaultVideoTile"

    override var state: VideoTileState = VideoTileState(tileId,
                                                        attendeeId,
                                                        videoStreamContentWidth,
                                                        videoStreamContentHeight,
                                                        VideoPauseState.Unpaused,
                                                        isLocalTile)
    override var videoRenderView: VideoRenderView? = null

    override fun bind(bindParams: Any?, videoRenderView: VideoRenderView?) {
        logger.info(TAG, "Binding the View to Tile")
        if (videoRenderView is EglRenderView) {
            videoRenderView.init(logger)
        }
        this.videoRenderView = videoRenderView
    }

    override fun renderFrame(frame: VideoFrame) {
        videoRenderView?.onFrameCaptured(frame)
    }

    override fun unbind() {
        logger.info(TAG, "Unbinding the View from Tile")
        if (videoRenderView is EglRenderView) {
            (videoRenderView as EglRenderView).dispose()
        }
        videoRenderView = null
    }

    override fun setPauseState(pauseState: VideoPauseState) {
        this.state.pauseState = pauseState
    }
}
