/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.realtime

import com.amazonaws.services.chime.sdk.meetings.realtime.datamessage.DataMessageObserver
import java.security.InvalidParameterException

/**
 * [RealtimeControllerFacade] controls aspects meetings concerning realtime UX
 * that for performance, privacy, or other reasons should be implemented using
 * the most direct path. Callbacks generated by this interface should be
 * consumed synchronously and without business logic dependent on the UI state
 * where possible.
 *
 * Events will be passed through [RealtimeObserver], which in turn provides consumers the
 * volume/mute/signal/attendee callbacks that can be used to render in the UI.
 * Data Messages will be passed through [DataMessageObserver].
 */
interface RealtimeControllerFacade {

    /**
     * Mute the audio input.
     *
     * @return Boolean whether the mute action succeeded
     */
    fun realtimeLocalMute(): Boolean

    /**
     * Unmutes the audio input.
     *
     * @return Boolean whether the unmute action succeeded
     */
    fun realtimeLocalUnmute(): Boolean

    /**
     * Subscribes to real time events with an observer
     *
     * @param observer: [RealtimeObserver] - Observer that handles real time events
     */
    fun addRealtimeObserver(observer: RealtimeObserver)

    /**
     * Unsubscribes from real time events by removing the specified observer
     *
     * @param observer: [RealtimeObserver] - Observer that handles real time events
     */
    fun removeRealtimeObserver(observer: RealtimeObserver)

    /**
     * Send message via data channel. Messages are only expected to be sent after audio video has started,
     * otherwise will be ignored.
     * Even though one can send data messages to any valid topic,
     * in order to receive the messages from the given topic, one need to subscribed to the topic
     * by calling [addRealtimeDataMessageObserver].
     * LifetimeMs specifies milliseconds for the given message can be stored in server side.
     * Up to 1024 messages may be stored for a maximum of 5 minutes.
     *
     * @param topic: String - topic the message is sent to
     * @param data: Any - data payload, it can be ByteArray, String or other serializable object,
     * which will be convert to ByteArray
     * @param lifetimeMs: Int - the milliseconds of lifetime that is available to late subscribers, default as 0
     * @throws [InvalidParameterException] when topic is not match regex "^[a-zA-Z0-9_-]{1,36}$",
     * or data size is over 2kb, or lifetime ms is negative
     */
    fun realtimeSendDataMessage(topic: String, data: Any, lifetimeMs: Int = 0)

    /**
     *  Subscribes to receive message on a topic, there could be multiple
     *  observers per topic
     *
     * @param topic: String - topic of messages for subscription
     * @param observer: [DataMessageObserver] - observer that handles the data message events
     */
    fun addRealtimeDataMessageObserver(topic: String, observer: DataMessageObserver)

    /**
     * Unsubscribes from a message topic
     *
     * @param topic: String - topic of messages for unsubscription
     */
    fun removeRealtimeDataMessageObserverFromTopic(topic: String)

    /**
     * Toggle Voice Focus (ML-based noise suppression) on the audio input.
     *
     * @return Boolean whether the toggle action succeeded
     */
    fun realtimeToggleVoiceFocus(on: Boolean): Boolean

    /**
     * Checks if Voice Focus is running
     *
     * @return Boolean whether Voice Focus is running or not
     */
    fun isVoiceFocusOn(): Boolean
}
