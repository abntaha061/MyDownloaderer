package com.example

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PureDownloadApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Python via Chaquopy if not already started
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
