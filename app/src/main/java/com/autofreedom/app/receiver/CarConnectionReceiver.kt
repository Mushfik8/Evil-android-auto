package com.autofreedom.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives car connection events from Android Auto.
 * Detects when the phone connects to / disconnects from a car.
 *
 * Connection types:
 * - 0: Not connected
 * - 1: Connected to Android Auto (projected)
 * - 2: Connected to Android Automotive OS (native)
 */
class CarConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarConnectionReceiver"
        const val ACTION_CAR_CONNECTION_UPDATED =
            "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        const val EXTRA_CONNECTION_TYPE = "androidx.car.app.connection.extra.CONNECTION_TYPE"

        var isConnected = false
            private set
        var connectionType = 0
            private set
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CAR_CONNECTION_UPDATED) {
            connectionType = intent.getIntExtra(EXTRA_CONNECTION_TYPE, 0)
            isConnected = connectionType != 0

            val typeStr = when (connectionType) {
                0 -> "Disconnected"
                1 -> "Android Auto (Projected)"
                2 -> "Android Automotive OS (Native)"
                else -> "Unknown ($connectionType)"
            }

            Log.i(TAG, "Car connection updated: $typeStr")

            // Broadcast to any interested activities
            val localIntent = Intent("com.autofreedom.CAR_CONNECTION_CHANGED").apply {
                putExtra("connected", isConnected)
                putExtra("type", connectionType)
            }
            context.sendBroadcast(localIntent)
        }
    }
}
