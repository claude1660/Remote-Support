package com.example.plainremote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.NetworkInterface
import java.util.Collections

/**
 * Home screen: lets the device owner start/stop the local web server,
 * grant the screen-capture permission for remote viewing, and jump to
 * system settings to enable the accessibility service used for
 * simulated taps. Nothing here can be triggered remotely - every
 * permission grant requires a tap on this physical device.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var urlContainer: LinearLayout
    private lateinit var internetServiceSwitch: Switch
    private lateinit var sessionCodeText: TextView
    private var currentSessionCode: String? = null

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCaptureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startWebServer(result.resultCode, result.data)
            } else {
                serviceSwitch.isChecked = false
                statusText.text = "Screen-capture permission denied - file browsing still works, but remote screen view will be unavailable."
                startWebServer(0, null)
            }
        }

    private val internetScreenCaptureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startInternetSession(result.resultCode, result.data)
            } else {
                internetServiceSwitch.isChecked = false
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        serviceSwitch = findViewById(R.id.serviceSwitch)
        urlContainer = findViewById(R.id.urlContainer)
        internetServiceSwitch = findViewById(R.id.internetServiceSwitch)
        sessionCodeText = findViewById(R.id.sessionCodeText)
        val enableAccessibilityButton = findViewById<Button>(R.id.enableAccessibilityButton)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Ask for screen-capture permission every time we start,
                // as required by Android for MediaProjection.
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                stopService(Intent(this, WebServerService::class.java))
                statusText.text = "Service stopped"
                urlContainer.removeAllViews()
            }
        }

        enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        internetServiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                internetScreenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                stopService(Intent(this, RemoteSessionService::class.java))
                currentSessionCode = null
                sessionCodeText.visibility = android.view.View.GONE
            }
        }
    }

    private fun startInternetSession(resultCode: Int, data: Intent) {
        val code = SignalingClient.randomCode()
        currentSessionCode = code
        val intent = Intent(this, RemoteSessionService::class.java).apply {
            putExtra(RemoteSessionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RemoteSessionService.EXTRA_RESULT_DATA, data)
            putExtra(RemoteSessionService.EXTRA_SESSION_CODE, code)
        }
        ContextCompat.startForegroundService(this, intent)

        sessionCodeText.text = code
        sessionCodeText.visibility = android.view.View.VISIBLE
    }

    private fun startWebServer(resultCode: Int, data: Intent?) {
        val intent = Intent(this, WebServerService::class.java).apply {
            putExtra(WebServerService.EXTRA_RESULT_CODE, resultCode)
            putExtra(WebServerService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)

        val ip = getLocalIpAddress()
        statusText.text = if (ip != null) "Service running" else "Service running (no Wi-Fi IP found)"

        urlContainer.removeAllViews()
        if (ip != null) {
            val label = TextView(this)
            label.text = "http://$ip:8080"
            label.textSize = 18f
            urlContainer.addView(label)
        }
    }

    /** Finds the phone's local Wi-Fi IP so it can be shown to the user. */
    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return Formatter.formatIpAddress(ipInt)
            }
        } catch (_: Exception) {
        }
        // Fallback: scan network interfaces for a non-loopback IPv4 address.
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
