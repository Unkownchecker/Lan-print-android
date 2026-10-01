package com.lanprint.android

import androidx.activity.result.contract.ActivityResultContracts
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private var service: PrintServerService? = null
    private var bound = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val mainScope = MainScope()

    companion object {
        private val URL_PATTERN = Pattern.compile(
            "^https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=]+$"
        )
    }

    private lateinit var statusText: TextView
    private lateinit var usbStatusText: TextView
    private lateinit var pairingIdText: TextView
    private lateinit var relayUrlInput: EditText
    private lateinit var countdownText: TextView
    private lateinit var jobsText: TextView
    private lateinit var firmwareStatusText: TextView

    private val firmwarePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty() && service?.installFirmwareFile(bytes) == true) {
                Toast.makeText(this, getString(R.string.firmware_loaded), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.connect_printer_first), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.couldnt_read_file, e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
        refreshFirmwareStatus()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PrintServerService.LocalBinder).getService()
            bound = true
            wireCallbacks()
            refreshAll()
            checkForAlreadyAttachedDevice()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        usbStatusText = findViewById(R.id.usbStatusText)
        pairingIdText = findViewById(R.id.pairingIdText)
        relayUrlInput = findViewById(R.id.relayUrlInput)
        countdownText = findViewById(R.id.countdownText)
        jobsText = findViewById(R.id.jobsText)
        firmwareStatusText = findViewById(R.id.firmwareStatusText)

        findViewById<Button>(R.id.saveRelayBtn).setOnClickListener {
            val url = relayUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, getString(R.string.please_enter_relay_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isValidUrl(url)) {
                Toast.makeText(this, getString(R.string.invalid_url_format), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service?.setRelayUrl(url)
            refreshAll()
            Toast.makeText(this, getString(R.string.relay_url_saved), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.copyIdBtn).setOnClickListener {
            val id = service?.getPairingId().orEmpty()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Pairing ID", id))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.regenerateIdBtn).setOnClickListener {
            service?.regenerateId()
            refreshAll()
            Toast.makeText(this, getString(R.string.new_pairing_id_generated), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.batteryOptBtn).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        findViewById<Button>(R.id.loadFirmwareBtn).setOnClickListener {
            firmwarePicker.launch("*/*")
        }

        requestNotificationPermissionIfNeeded()

        val serviceIntent = Intent(this, PrintServerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        startCountdownTicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            device?.let { requestUsbPermissionAndConnect(it) }
        }
    }

    private fun checkForAlreadyAttachedDevice() {
        val svc = service ?: return
        val candidate = svc.getUsbManagerHelper().findCandidateDevices().firstOrNull() ?: return
        requestUsbPermissionAndConnect(candidate)
    }

    private fun requestUsbPermissionAndConnect(device: UsbDevice) {
        val svc = service ?: return
        mainScope.launch {
            val usbHelper = svc.getUsbManagerHelper()
            if (!usbHelper.hasPermission(device)) {
                val granted = usbHelper.requestPermission(device)
                if (!granted) {
                    usbStatusText.text = getString(R.string.usb_permission_denied)
                    return@launch
                }
            }
            svc.tryConnectDevice(device)
        }
    }

    private fun wireCallbacks() {
        val svc = service ?: return
        svc.onStatusUpdate = { uiHandler.post { refreshStatus() } }
        svc.onNextReconnectUpdate = { uiHandler.post { refreshCountdown() } }
        svc.onJobsUpdate = { uiHandler.post { refreshJobs() } }
        svc.onUsbStatusUpdate = { text -> uiHandler.post { usbStatusText.text = text; refreshFirmwareStatus() } }
    }

    private fun refreshAll() {
        refreshStatus()
        refreshCountdown()
        refreshJobs()
        refreshFirmwareStatus()
        relayUrlInput.setText(service?.getRelayUrl().orEmpty())
        pairingIdText.text = service?.getPairingId()?.ifBlank { "——————" } ?: "——————"
        usbStatusText.text = service?.getConnectedPrinterName()?.let { "Connected: $it" } ?: "No printer connected"
    }

    private fun refreshFirmwareStatus() {
        firmwareStatusText.text = service?.getFirmwareStatus() ?: "—"
    }

    private fun refreshStatus() {
        val status = service?.getConnectionStatus() ?: "unconfigured"
        statusText.text = when (status) {
            "connected" -> getString(R.string.connected_reachable_anywhere)
            "connecting" -> getString(R.string.connecting_to_relay)
            "disconnected" -> getString(R.string.disconnected_retrying)
            else -> getString(R.string.not_set_up)
        }
        pairingIdText.text = service?.getPairingId()?.ifBlank { getString(R.string.pairing_id_placeholder) } ?: getString(R.string.pairing_id_placeholder)
    }

    private fun refreshCountdown() {
        val target = service?.getNextReconnectAt() ?: 0L
        if (target <= 0L) {
            countdownText.text = getString(R.string.next_connection_refresh, "—")
            return
        }
        val remaining = target - System.currentTimeMillis()
        if (remaining <= 0) {
            countdownText.text = getString(R.string.next_connection_refresh_soon)
        } else {
            val totalSeconds = remaining / 1000
            val timeStr = "${totalSeconds / 60}m ${(totalSeconds % 60).toString().padStart(2, '0')}s"
            countdownText.text = getString(R.string.next_connection_refresh, timeStr)
        }
    }

    private fun startCountdownTicker() {
        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                refreshCountdown()
                uiHandler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun refreshJobs() {
        val jobs = service?.getRecentJobs().orEmpty()
        if (jobs.isEmpty()) {
            jobsText.text = getString(R.string.nothing_printed_yet)
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        jobsText.text = jobs.take(10).joinToString("\n") { j ->
            val mark = if (j.ok) "✓" else "✗"
            "$mark ${fmt.format(j.time)}  ${j.name} → ${j.printer}"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.already_exempted_battery_opt), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            URL_PATTERN.matcher(url).matches()
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.onStatusUpdate = null
            service?.onNextReconnectUpdate = null
            service?.onJobsUpdate = null
            service?.onUsbStatusUpdate = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
