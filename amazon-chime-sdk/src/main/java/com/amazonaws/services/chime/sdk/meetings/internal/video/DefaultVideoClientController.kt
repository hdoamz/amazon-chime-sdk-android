/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.internal.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGLContext
import androidx.core.content.ContextCompat
import com.amazonaws.services.chime.sdk.BuildConfig
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source.VideoSource
import com.amazonaws.services.chime.sdk.meetings.session.MeetingSessionConfiguration
import com.amazonaws.services.chime.sdk.meetings.utils.logger.Logger
import com.google.gson.Gson
import com.xodee.client.video.VideoClient
import com.xodee.client.video.VideoClientCapturer
import com.xodee.client.video.VideoDevice
import java.security.InvalidParameterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class DefaultVideoClientController(
    private val context: Context,
    private val logger: Logger,
    private val videoClientStateController: VideoClientStateController,
    private val videoClientObserver: VideoClientObserver,
    private val configuration: MeetingSessionConfiguration,
    private val videoClientFactory: VideoClientFactory,
    private val sharedEglContext: EGLContext?
) : VideoClientController,
    VideoClientLifecycleHandler {

    private val DATA_MAX_SIZE = 2048
    private val TOPIC_REGEX = "^[a-zA-Z0-9_-]{1,36}$".toRegex()
    private val TAG = "DefaultVideoClientController"

    private val VIDEO_CLIENT_FLAG_ENABLE_USE_HW_DECODE_AND_RENDER = 64
    /**
     * This flag will enable higher resolution for videos
     */
    private val VIDEO_CLIENT_FLAG_ENABLE_TWO_SIMULCAST_STREAMS = 4096
    private val VIDEO_CLIENT_FLAG_DISABLE_CAPTURER = 262144

    private val gson = Gson()
    private val permissions = arrayOf(
        Manifest.permission.CAMERA
    )

    private var videoSourceAdapter: VideoSourceAdapter? = null

    init {
        videoClientStateController.bindLifecycleHandler(this)
    }

    private var videoClient: VideoClient? = null

    override fun start() {
        videoClientStateController.start()
    }

    override fun stopAndDestroy() {
        runBlocking {
            stopAsync()
        }
    }

    private suspend fun stopAsync() {
        withContext(Dispatchers.Default) {
            videoClientStateController.stop()
        }
    }

    override fun startLocalVideo() {
        if (!videoClientStateController.canAct(VideoClientState.INITIALIZED)) return

        logger.info(TAG, "Starting local video")
        val hasPermission: Boolean = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) {
            throw SecurityException(
                "Missing necessary permissions for WebRTC: ${permissions.joinToString(
                    separator = ", ",
                    prefix = "",
                    postfix = ""
                )}"
            )
        }

        videoClient?.setSending(true)
    }

    override fun stopLocalVideo() {
        if (!videoClientStateController.canAct(VideoClientState.INITIALIZED)) return

        logger.info(TAG, "Stopping local video")
        videoClient?.setSending(false)
    }

    override fun startRemoteVideo() {
        if (!videoClientStateController.canAct(VideoClientState.INITIALIZED)) return

        logger.info(TAG, "Starting remote video")
        videoClient?.setReceiving(true)
    }

    override fun stopRemoteVideo() {
        if (!videoClientStateController.canAct(VideoClientState.INITIALIZED)) return

        logger.info(TAG, "Stopping remote video")
        videoClient?.setReceiving(false)
    }

    override fun getActiveCamera(): VideoDevice? {
        return null
    }

    override fun switchCamera() {
        return
    }

    override fun chooseVideoSource(source: VideoSource?) {
        logger.info(TAG, "Choosing external video source in media client")
        if (source == null) {
            logger.info(TAG, "Choosing null external video source in media client")
            videoClient?.setExternalVideoSource(null)
            return
        }

        videoSourceAdapter = VideoSourceAdapter(source, logger)
        videoClient?.setExternalVideoSource(videoSourceAdapter)
    }

    override fun setRemotePaused(isPaused: Boolean, videoId: Int) {
        if (!videoClientStateController.canAct(VideoClientState.STARTED)) return

        logger.info(TAG, "Set pause for videoId: $videoId, isPaused: $isPaused")
        videoClient?.setRemotePause(videoId, isPaused)
    }

    override fun getConfiguration(): MeetingSessionConfiguration {
        return configuration
    }

    override fun sendDataMessage(topic: String, data: Any, lifetimeMs: Int) {
        if (!videoClientStateController.canAct(VideoClientState.STARTED)) return

        val byteArray = data as? ByteArray
            ?: if (data is String) {
                data.toByteArray()
            } else {
                gson.toJson(data).toByteArray()
            }

        if (!TOPIC_REGEX.matches(topic)) {
            throw InvalidParameterException("Invalid topic")
        }
        if (byteArray.size > DATA_MAX_SIZE) {
            throw InvalidParameterException("Data size has to be less than or equal to 2048 bytes")
        }
        if (lifetimeMs < 0) {
            throw InvalidParameterException("The life time of the message has to be non negative")
        }

        videoClient?.sendDataMessage(topic, byteArray, lifetimeMs)
    }

    override fun initializeVideoClient() {
        logger.info(TAG, "Initializing video client")
        initializeAppDetailedInfo()
        VideoClient.initializeGlobals(context)
        VideoClientCapturer.getInstance(context)
        videoClient = videoClientFactory.getVideoClient(videoClientObserver)
        videoClientObserver.notifyVideoTileObserver { observer -> observer.initialize() }
    }

    override fun startVideoClient() {
        logger.info(TAG, "Starting video client")
        videoClient?.setReceiving(false)
        var flag = 0
        flag = flag or VIDEO_CLIENT_FLAG_ENABLE_TWO_SIMULCAST_STREAMS
        flag = flag or VIDEO_CLIENT_FLAG_DISABLE_CAPTURER
        flag = flag or VIDEO_CLIENT_FLAG_ENABLE_USE_HW_DECODE_AND_RENDER
        videoClient?.startServiceV2(
            "",
            "",
            configuration.meetingId,
            configuration.credentials.joinToken,
            false,
            0,
            flag,
            sharedEglContext
        )

        videoClient?.setExternalVideoSource(videoSourceAdapter)
    }

    override fun stopVideoClient() {
        logger.info(TAG, "Stopping video client")
        videoClient?.stopService()
    }

    override fun destroyVideoClient() {
        logger.info(TAG, "Destroying video client")
        videoClientObserver.notifyVideoTileObserver { observer -> observer.destroy() }
        videoClient?.destroy()
        videoClient = null
        VideoClient.finalizeGlobals()
    }

    private fun initializeAppDetailedInfo() {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        val osVersion = android.os.Build.VERSION.RELEASE
        val packageName = context.packageName
        val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
        val appVer = packageInfo.versionName
        val appCode = packageInfo.versionCode.toString()
        val clientSource = "amazon-chime-sdk"
        val sdkVersion = BuildConfig.VERSION_NAME

        VideoClient.AppDetailedInfo.initialize(
            String.format("Android %s", appVer),
            appCode,
            model,
            manufacturer,
            osVersion,
            clientSource,
            sdkVersion
        )
    }
}
