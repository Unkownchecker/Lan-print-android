package com.lanprint.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class PrintServerService : Service(), PrinterBackend {

    companion object {
        const val CHANNEL_ID = "lan_print_status"
        const val JOBS_CHANNEL_ID = "lan_print_jobs"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "lan_print_prefs"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_PAIRING_ID = "pairing_id"
        private const val ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous chars

        fun generateId(): String {
            val random = SecureRandom()
            return (1..6).map { ID_CHARS[random.nextInt(ID_CHARS.length)] }.joinToString("")
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PrintServerService = this@PrintServerService
    }

    private val binder = LocalBinder()
    private lateinit var prefs: SharedPreferences
    private lateinit var usbManager: UsbPrinterManager
    private lateinit var manualDuplexManager: ManualDuplexManager
    private lateinit var relayClient: RelayClient
    private val scope = CoroutineScope(Dispatchers.IO)

    private var connectedPrinter: UsbPrinterManager.ConnectedPrinter? = null
    private var connectionStatus = "unconfigured"
    private var nextReconnectAt = 0L
    private var firmwareSentThisConnection = false
    private val recentJobs = mutableListOf<JobEntry>()

    data class JobEntry(val name: String, val printer: String, val ok: Boolean, val time: Long)

    // ---- UI-facing state, polled/observed by MainActivity ----
    var onStatusUpdate: ((String) -> Unit)? = null
    var onNextReconnectUpdate: ((Long) -> Unit)? = null
    var onJobsUpdate: (() -> Unit)? = null
    var onUsbStatusUpdate: ((String) -> Unit)? = null

    fun getRelayUrl(): String = prefs.getString(KEY_RELAY_URL, "") ?: ""
    fun getPairingId(): String = prefs.getString(KEY_PAIRING_ID, "") ?: ""
    fun getConnectionStatus(): String = connectionStatus
    fun getNextReconnectAt(): Long = nextReconnectAt
    fun getRecentJobs(): List<JobEntry> = recentJobs.toList()
    fun getConnectedPrinterName(): String? = connectedPrinter?.model?.displayName
    fun getFirmwareStatus(): String {
        val printer = connectedPrinter ?: return applicationContext.getString(R.string.no_printer_connected)
        val fw = firmwareFile(printer.model)
        return when {
            !fw.exists() -> applicationContext.getString(R.string.no_firmware_loaded, printer.model.firmwareModelName)
            firmwareSentThisConnection -> applicationContext.getString(R.string.firmware_sent_connection)
            else -> applicationContext.getString(R.string.firmware_ready)
        }
    }

    /** Where a firmware file for [model]'s required firmware name is stored, if the user has provided one. */
    fun firmwareFile(model: PrinterModel): File =
        File(filesDir, "firmware-${model.firmwareModelName}.dl")

    /** Called by the Activity after the user picks a firmware file for the currently connected model. */
    fun installFirmwareFile(bytes: ByteArray): Boolean {
        val model = connectedPrinter?.model ?: return false
        firmwareFile(model).writeBytes(bytes)
        firmwareSentThisConnection = false
        connectedPrinter?.let { sendFirmwareIfNeeded(it) }
        return true
    }

    private fun sendFirmwareIfNeeded(printer: UsbPrinterManager.ConnectedPrinter) {
        if (firmwareSentThisConnection) return
        val fw = firmwareFile(printer.model)
        if (!fw.exists()) {
            onUsbStatusUpdate?.invoke(
                applicationContext.getString(R.string.connected_no_firmware, printer.model.displayName)
            )
            return
        }
        val ok = usbManager.sendFirmware(printer, fw.readBytes())
        firmwareSentThisConnection = ok
        onUsbStatusUpdate?.invoke(
            if (ok) applicationContext.getString(R.string.connected_firmware_sent, printer.model.displayName)
            else applicationContext.getString(R.string.firmware_upload_failed, printer.model.displayName)
        )
    }

    fun setRelayUrl(url: String) {
        var id = getPairingId()
        if (id.isBlank()) {
            id = generateId()
            prefs.edit().putString(KEY_PAIRING_ID, id).apply()
        }
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
        relayClient.configure(url, id)
    }

    fun regenerateId(): String {
        val id = generateId()
        prefs.edit().putString(KEY_PAIRING_ID, id).apply()
        relayClient.configure(getRelayUrl(), id)
        return id
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) tryConnectDevice(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == connectedPrinter?.device) {
                        connectedPrinter?.let { usbManager.disconnect(it) }
                        connectedPrinter = null
                        firmwareSentThisConnection = false
                        onUsbStatusUpdate?.invoke("No printer connected")
                        updateNotification()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        usbManager = UsbPrinterManager(this)
        manualDuplexManager = ManualDuplexManager(this)
        relayClient = RelayClient(
            backend = this,
            onStatusChange = { status ->
                connectionStatus = status
                onStatusUpdate?.invoke(status)
                updateNotification()
            },
            onNextReconnectChange = { ts ->
                nextReconnectAt = ts
                onNextReconnectUpdate?.invoke(ts)
            },
            onJobRecorded = { name, printer, ok ->
                recentJobs.add(0, JobEntry(name, printer, ok, System.currentTimeMillis()))
                while (recentJobs.size > 30) recentJobs.removeAt(recentJobs.size - 1)
                onJobsUpdate?.invoke()
                notifyJobResult(name, printer, ok)
            },
        )

        // Clean up old cached files on startup
        cleanupOldCacheFiles()

        createNotificationChannels()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, filter)
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val savedUrl = getRelayUrl()
        val savedId = getPairingId()
        if (savedUrl.isNotBlank() && savedId.isNotBlank()) {
            relayClient.configure(savedUrl, savedId)
        }
        relayClient.start()

        scope.launch { autoConnectExistingDevice() }
    }

    private fun autoConnectExistingDevice() {
        usbManager.findCandidateDevices().firstOrNull()?.let { tryConnectDevice(it) }
    }

    /** Called both from USB-attach broadcasts and from the Activity after it obtains permission. */
    fun tryConnectDevice(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            onUsbStatusUpdate?.invoke("Waiting for USB permission…")
            return
        }
        val printer = usbManager.connect(device) { reason ->
            onUsbStatusUpdate?.invoke(reason)
        }
        if (printer != null) {
            connectedPrinter?.let { usbManager.disconnect(it) }
            connectedPrinter = printer
            firmwareSentThisConnection = false
            onUsbStatusUpdate?.invoke("Connected: ${printer.model.displayName}")
            updateNotification()
            sendFirmwareIfNeeded(printer)
        }
    }

    fun getUsbManagerHelper(): UsbPrinterManager = usbManager

    /** Cleans up cached files older than 24 hours to prevent storage bloat. */
    private fun cleanupOldCacheFiles() {
        val now = System.currentTimeMillis()
        val maxAge = 24 * 60 * 60 * 1000L // 24 hours

        val cacheDirs = listOf(
            File(cacheDir, "incoming"),
            File(cacheDir, "xqx-work"),
            File(cacheDir, "manual-duplex")
        )

        cacheDirs.forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > maxAge) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            // Ignore deletion errors
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        relayClient.stop()
        connectedPrinter?.let { usbManager.disconnect(it) }
        try { unregisterReceiver(usbAttachReceiver) } catch (_: Exception) {}
        // Clean up all cache files on service stop
        cleanupAllCacheFiles()
        super.onDestroy()
    }

    /** Cleans up all cache files (called on service stop). */
    private fun cleanupAllCacheFiles() {
        val cacheDirs = listOf(
            File(cacheDir, "incoming"),
            File(cacheDir, "xqx-work"),
            File(cacheDir, "manual-duplex")
        )

        cacheDirs.forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        // Ignore deletion errors
                    }
                }
            }
        }
    }

    // ---- PrinterBackend ----

    override fun listPrinters(): List<PrinterBackend.PrinterInfo> {
        val printer = connectedPrinter ?: return emptyList()
        return listOf(PrinterBackend.PrinterInfo(printer.model.displayName, PAPER_SIZES.keys.toList()))
    }

    override fun print(filename: String, fileBytes: ByteArray, options: JSONObject): PrinterBackend.PrintResult {
        val printer = connectedPrinter
            ?: return PrinterBackend.PrintResult(ok = false, error = PrintError.NoPrinterConnected().userFriendlyMessage)

        if (!firmwareSentThisConnection) {
            return PrinterBackend.PrintResult(
                ok = false,
                error = PrintError.FirmwareNotLoaded(printer.model.firmwareModelName).userFriendlyMessage
            )
        }

        if (!filename.lowercase().endsWith(".pdf")) {
            return PrinterBackend.PrintResult(
                ok = false,
                error = PrintError.InvalidFileType().userFriendlyMessage
            )
        }

        val workDir = File(cacheDir, "incoming").apply { mkdirs() }
        val pdfFile = File(workDir, "job-${System.currentTimeMillis()}.pdf")
        pdfFile.writeBytes(fileBytes)

        val printOptions = XqxPrinter.PrintOptions(
            paperSize = options.optString("paperSize", DEFAULT_PAPER_SIZE).let {
                if (it.isBlank() || it == "default") DEFAULT_PAPER_SIZE else it
            },
            orientationLandscape = options.optString("orientation") == "landscape",
            copies = options.optString("copies", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )

        try {
            if (options.optString("side") == "manual") {
                val start = manualDuplexManager.start(pdfFile.absolutePath, printer.model, printOptions)
                    ?: return PrinterBackend.PrintResult(ok = false, error = PrintError.SinglePageManualDuplex().userFriendlyMessage)

                val err = XqxPrinter.printPages(this, usbManager, printer, pdfFile.absolutePath, start.oddPages, printOptions)
                if (err != null) {
                    manualDuplexManager.cancel(start.jobId)
                    return PrinterBackend.PrintResult(ok = false, error = err)
                }
                return PrinterBackend.PrintResult(
                    ok = true, method = "manual-duplex", phase = 1, jobId = start.jobId,
                    totalPages = start.totalPages, oddCount = start.oddCount, evenCount = start.evenCount,
                )
            }

            val allPages = (1..XqxPrinter.pdfPageCount(pdfFile.absolutePath)).toList()
            val err = XqxPrinter.printPages(this, usbManager, printer, pdfFile.absolutePath, allPages, printOptions)
            return if (err != null) PrinterBackend.PrintResult(ok = false, error = err)
            else PrinterBackend.PrintResult(ok = true, method = "direct")
        } finally {
            pdfFile.delete()
        }
    }

    override fun continueManual(jobId: String, reverseEven: Boolean): PrinterBackend.PrintResult {
        val job = manualDuplexManager.get(jobId)
            ?: return PrinterBackend.PrintResult(ok = false, error = PrintError.JobExpired().userFriendlyMessage)
        val printer = connectedPrinter
            ?: return PrinterBackend.PrintResult(ok = false, error = PrintError.NoPrinterConnected().userFriendlyMessage)

        val even = manualDuplexManager.evenPages(job.totalPages, reverseEven)
        val err = XqxPrinter.printPages(this, usbManager, printer, job.pdfPath, even, job.options)
        manualDuplexManager.finish(jobId)
        return if (err != null) PrinterBackend.PrintResult(ok = false, error = err)
        else PrinterBackend.PrintResult(ok = true, method = "manual-duplex", phase = 2, evenCount = even.size)
    }

    override fun cancelManual(jobId: String) {
        manualDuplexManager.cancel(jobId)
    }

    // ---- Notifications ----

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.notification_channel_status), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(JOBS_CHANNEL_ID, applicationContext.getString(R.string.notification_channel_jobs), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(): Notification {
        val statusText = when (connectionStatus) {
            "connected" -> applicationContext.getString(R.string.notification_connected)
            "connecting" -> applicationContext.getString(R.string.notification_connecting)
            "disconnected" -> applicationContext.getString(R.string.notification_reconnecting)
            else -> applicationContext.getString(R.string.notification_not_setup)
        }
        val printerText = connectedPrinter?.model?.displayName ?: applicationContext.getString(R.string.no_printer_connected)
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notification_printer_prefix, printerText))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_send) // replaced by a real icon resource at build time
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun notifyJobResult(name: String, printer: String, ok: Boolean) {
        val title = if (ok) applicationContext.getString(R.string.print_job_printed) else applicationContext.getString(R.string.print_job_failed)
        val printerPart = if (printer.isNotBlank() && printer != "-") applicationContext.getString(R.string.on_printer, printer) else ""
        val body = if (ok) applicationContext.getString(R.string.print_success, name, printerPart) else applicationContext.getString(R.string.print_failure, name, printerPart)
        val notification = NotificationCompat.Builder(this, JOBS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
