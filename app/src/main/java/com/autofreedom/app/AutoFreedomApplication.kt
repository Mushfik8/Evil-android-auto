package com.autofreedom.app

import android.app.Application
import android.util.Log

class AutoFreedomApplication : Application() {

    companion object {
        const val TAG = "AutoFreedom"
        lateinit var instance: AutoFreedomApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AutoFreedom Application initialized")
    }
}
