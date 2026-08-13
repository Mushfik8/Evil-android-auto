package com.autofreedom.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto. This service is launched by the Android Auto
 * host when the user selects AutoFreedom from the car screen.
 */
class AutoFreedomCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // In production, restrict to known AA hosts. For development, allow all.
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return AutoFreedomSession()
    }
}
