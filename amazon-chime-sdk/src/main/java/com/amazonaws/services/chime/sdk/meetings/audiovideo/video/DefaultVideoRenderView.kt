/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazonaws.services.chime.sdk.meetings.audiovideo.video

import android.content.Context
import android.util.AttributeSet
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.DefaultEglVideoRenderView


class DefaultVideoRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : DefaultEglVideoRenderView(context, attrs, defStyle)