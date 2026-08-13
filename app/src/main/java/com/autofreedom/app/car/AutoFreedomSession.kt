package com.autofreedom.app.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autofreedom.app.car.screens.MainScreen

/**
 * Manages the lifecycle of one Android Auto connection session.
 * Each time the user opens AutoFreedom on the car screen, a new Session is created.
 */
class AutoFreedomSession : Session(), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return MainScreen(carContext)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Session started — car is connected
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Session ended — car disconnected
    }
}
