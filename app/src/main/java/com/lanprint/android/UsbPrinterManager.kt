package com.lanprint.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UsbPrinterManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.lanprint.android.USB_PERMISSION"
        private const val CONTROL_TIMEOUT_MS = 3000
        private const val BULK_TIMEOUT_MS = 15000

        // USB recipient types (not defined in UsbConstants)
        private const val USB_RECIP_DEVICE = 0x00
        private const val USB_RECIP_INTERFACE = 0x01
        private const val USB_RECIP_ENDPOINT = 0x02
        private const val USB_RECIP_OTHER = 0x03

        // USB Printer Class Specification 1.1, section 4.2.1 GET_DEVICE_ID:
        // bmRequestType=0xA1 (IN | Class | Interface), bRequest=0,
        // wValue=configuration index, wIndex=(interface index << 8) | altSetting.
        // Response: 2-byte big-endian length (INCLUDING those 2 bytes) followed
        // by the ASCII IEEE-1284 device ID string.
        private const val GET_DEVICE_ID_REQUEST_TYPE =
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIP_INTERFACE
        private const val GET_DEVICE_ID_BREQUEST = 0
    }

    data class ConnectedPrinter(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val outEndpoint: UsbEndpoint,
        val model: PrinterModel,
        val deviceIdString: String,
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Every currently-attached USB device whose vendor ID is HP's. */
    fun findCandidateDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { it.vendorId == HP_VENDOR_ID }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Suspends until the user grants or denies USB permission for [device]. */
    suspend fun requestPermission(device: UsbDevice): Boolean = suspendCoroutine { cont ->
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                cont.resume(granted)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Opens [device], claims its USB Printer Class interface, reads its
     * IEEE-1284 device ID string to identify which supported model it is,
     * and returns everything needed to print to it. Returns null if the
     * device isn't one of [SUPPORTED_MODELS], with [onUnsupported] told why.
     */
    fun connect(device: UsbDevice, onUnsupported: (String) -> Unit = {}): ConnectedPrinter? {
        val printerInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
        if (printerInterface == null) {
            onUnsupported("This USB device has no Printer Class interface.")
            return null
        }

        val outEndpoint = (0 until printerInterface.endpointCount)
            .map { printerInterface.getEndpoint(it) }
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }
        if (outEndpoint == null) {
            onUnsupported("No bulk OUT endpoint found on the printer interface.")
            return null
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            onUnsupported("Could not open the USB device (permission or OS-level issue).")
            return null
        }

        if (!connection.claimInterface(printerInterface, true)) {
            onUnsupported("Could not claim the printer's USB interface (in use by another app/driver?).")
            connection.close()
            return null
        }

        val deviceIdString = readDeviceIdString(connection, printerInterface) ?: ""
        val model = matchModelByIds(device.vendorId, device.productId)
            ?: matchModelByDeviceId(deviceIdString)
        if (model == null) {
            onUnsupported(
                "Connected, but this doesn't look like a supported model " +
                    "(USB ID ${device.vendorId.toString(16)}:${device.productId.toString(16)}). " +
                    "Device ID reported: \"$deviceIdString\""
            )
            connection.releaseInterface(printerInterface)
            connection.close()
            return null
        }

        return ConnectedPrinter(device, connection, printerInterface, outEndpoint, model, deviceIdString)
    }

    private fun readDeviceIdString(connection: UsbDeviceConnection, iface: UsbInterface): String? {
        val buffer = ByteArray(1024)
        val wIndex = (iface.id shl 8) or 0
        val read = connection.controlTransfer(
            GET_DEVICE_ID_REQUEST_TYPE, GET_DEVICE_ID_BREQUEST, 0, wIndex, buffer, buffer.size, CONTROL_TIMEOUT_MS
        )
        if (read < 2) return null
        val declaredLen = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        val stringLen = (declaredLen - 2).coerceIn(0, read - 2)
        return try {
            String(buffer, 2, stringLen, Charsets.US_ASCII)
        } catch (e: Exception) {
            null
        }
    }

    /** Sends [data] to the printer's bulk OUT endpoint, chunked to a safe transfer size. */
    fun sendBulkData(printer: ConnectedPrinter, data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        android.util.Log.d("UsbPrinterManager", "Sending ${data.size} bytes to printer")
        val chunkSize = 16384
        var offset = 0
        var totalSent = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val sent = printer.connection.bulkTransfer(printer.outEndpoint, data, offset, len, BULK_TIMEOUT_MS)
            if (sent < 0) {
                android.util.Log.e("UsbPrinterManager", "USB transfer failed at offset $offset")
                return false
            }
            totalSent += sent
            offset += sent
        }
        android.util.Log.d("UsbPrinterManager", "Successfully sent $totalSent bytes to printer")
        return true
    }

    /**
     * These printers have no persistent firmware storage -- HP's own
     * firmware blob must be re-uploaded to the printer every time it's
     * power-cycled/reconnected, via a plain bulk write to the same
     * endpoint print data goes to (confirmed from foo2zjs's own hotplug
     * script, which does exactly this before the first print job). This
     * app doesn't bundle that firmware -- it's HP's proprietary property,
     * not something covered by foo2zjs's own GPL license -- see the
     * README for how to obtain it yourself.
     */
    fun sendFirmware(printer: ConnectedPrinter, firmwareBytes: ByteArray): Boolean =
        sendBulkData(printer, firmwareBytes)

    fun disconnect(printer: ConnectedPrinter) {
        try {
            printer.connection.releaseInterface(printer.usbInterface)
        } catch (_: Exception) {
        }
        printer.connection.close()
    }
}
