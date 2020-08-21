package com.amazonaws.services.chime.sdk.meetings.device

import android.util.Range

data class VideoDeviceFormat(
    var width: Int,
    var height: Int,
    var framerateRange: Range<Int>
)
