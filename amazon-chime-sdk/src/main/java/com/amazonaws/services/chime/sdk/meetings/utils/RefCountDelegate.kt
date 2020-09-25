package com.amazonaws.services.chime.sdk.meetings.utils

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

internal class RefCountDelegate(private val releaseCallback: Runnable) {
    private val refCount: AtomicInteger = AtomicInteger(1)

    private val id: Int = kotlin.random.Random.Default.nextInt()

    init {
//        Log.e("TEST", "retain $id $refCount")
//        Throwable().printStackTrace()
    }
    fun retain() {
        val updatedCount: Int = refCount.incrementAndGet()
//        Log.e("TEST", "retain $id $refCount")
//        Throwable().printStackTrace()
        check(updatedCount >= 2) { "retain() called on an object with refcount < 1" }
    }

    fun release() {
        val updatedCount: Int = refCount.decrementAndGet()
//        Log.e("TEST", "release $id $refCount")
//        Throwable().printStackTrace()
        check(updatedCount >= 0) { "release() called on an object with refcount < 1" }
        if (updatedCount == 0) {
            releaseCallback.run()
        }
    }
}