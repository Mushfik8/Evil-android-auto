package com.autofreedom.app.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autofreedom.app.R
import com.autofreedom.app.receiver.CarConnectionReceiver

/**
 * Phone-side companion activity.
 *
 * This is NOT shown on the car screen — it's the app UI on the phone itself.
 * Used for:
 * - Initial setup and permissions
 * - Showing connection status
 * - Settings
 *
 * All car-screen interaction goes through CarAppService → Screens.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnPermissions: Button

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra("connected", false)
            updateConnectionStatus(connected)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        btnPermissions = findViewById(R.id.btnPermissions)

        btnPermissions.setOnClickListener {
            requestRequiredPermissions()
        }

        updateConnectionStatus(CarConnectionReceiver.isConnected)
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.autofreedom.CAR_CONNECTION_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, filter)
        }
        updatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(connectionReceiver)
        } catch (_: Exception) {}
    }

    private fun updateConnectionStatus(connected: Boolean) {
        tvConnectionStatus.text = if (connected) {
            getString(R.string.phone_status_connected)
        } else {
            getString(R.string.phone_status_disconnected)
        }
    }

    private fun updatePermissionStatus() {
        val allGranted = getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            btnPermissions.text = getString(R.string.phone_permissions_granted)
            btnPermissions.isEnabled = false
            btnPermissions.alpha = 0.6f
        } else {
            btnPermissions.text = getString(R.string.phone_grant_permissions)
            btnPermissions.isEnabled = true
            btnPermissions.alpha = 1.0f
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions)
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        return permissions
    }
}
