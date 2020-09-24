package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

/**
 * [CaptureSourceObserver] observes events resulting from different types of capturers
 */
interface CaptureSourceObserver {
    /**
     * Called when the capture source has started successfully
     */
    fun onCaptureStarted()

    /**
     * Called when the capture source has stopped when expected
     */
    fun onCaptureStopped()

    /**
     * Called when the capture source failed permanently
     *
      @param error: [CaptureSourceError] - The reason why the source has stopped.
     */
    fun onCaptureFailed(error: CaptureSourceError)
}