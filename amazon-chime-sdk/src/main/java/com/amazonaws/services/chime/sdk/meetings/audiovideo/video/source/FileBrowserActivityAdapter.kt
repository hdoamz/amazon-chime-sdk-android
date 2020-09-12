package com.amazonaws.services.chime.sdk.meetings.audiovideo.video.source

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity


class FileBrowserActivityAdapter : AppCompatActivity() {
    interface Callback  {
        fun onFileBrowserActivityResult(resultCode: Int, intent: Intent)
    }
    companion object {
        lateinit var callback: Callback
    }

    private val REQUEST_CODE_FILE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(
                Intent.createChooser(intent, "Select a File to Upload"),
                REQUEST_CODE_FILE
            )
        } catch (ex: Exception) {
            println("browseClick :$ex") //android.content.ActivityNotFoundException ex
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_FILE -> {
                if (data != null) {
                    callback.onFileBrowserActivityResult(resultCode, data)
                }
                finishAndRemoveTask()
            }
        }
    }

}