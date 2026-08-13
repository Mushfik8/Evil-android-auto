package com.autofreedom.app

import android.app.Application
import android.util.Log
import com.autofreedom.app.server.LocalFileServer

class AutoFreedomApplication : Application() {

    companion object {
        const val TAG = "AutoFreedom"
        lateinit var instance: AutoFreedomApplication
            private set
    }

    val fileServer = LocalFileServer(8080)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AutoFreedom Application initialized")

        // Start local file server for browser access to device storage
        fileServer.start()
        Log.i(TAG, "Local file server started at ${fileServer.getBaseUrl()}")
    }

    override fun onTerminate() {
        super.onTerminate()
        fileServer.stop()
    }
}
