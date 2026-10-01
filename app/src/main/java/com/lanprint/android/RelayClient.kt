package com.lanprint.android

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

sealed class PrintError(val message: String, val userFriendlyMessage: String) {
    class NoPrinterConnected : PrintError(
        "No printer connected to this device.",
        "No printer connected. Please connect your printer and try again."
    )
    class FirmwareNotLoaded(modelName: String) : PrintError(
        "Firmware not loaded for $modelName",
        "Firmware not loaded for $modelName. Load the firmware file first (see README)."
    )
    class InvalidFileType : PrintError(
        "File type not supported",
        "This printer only accepts PDF files. Convert your file to PDF and try again."
    )
    class UsbTransferFailed : PrintError(
        "USB transfer failed",
        "Failed to send data to the printer. Try reconnecting the printer."
    )
    class ConversionFailed(exitCode: Int) : PrintError(
        "Raster conversion failed (exit code $exitCode)",
        "Failed to convert the document for printing. The file may be corrupted."
    )
    class ConversionProducedNoData : PrintError(
        "Conversion produced no data",
        "Failed to convert the document for printing. The file may be empty or corrupted."
    )
    class SinglePageManualDuplex : PrintError(
        "Single page cannot use manual duplex",
        "This file only has one page. Manual duplex requires at least 2 pages."
    )
    class JobExpired : PrintError(
        "Print job expired",
        "This print job has expired (20 minutes). Start again from the beginning."
    )
    class GeneralError(cause: String) : PrintError(
        cause,
        "An error occurred: $cause"
    )
}

/** What actually talks to the printer -- implemented by PrintServerService. */
interface PrinterBackend {
    data class PrinterInfo(val name: String, val paperSizes: List<String>)
    data class PrintResult(
        val ok: Boolean,
        val error: String? = null,
        val note: String? = null,
        val method: String? = null,
        val phase: Int? = null,
        val jobId: String? = null,
        val totalPages: Int? = null,
        val oddCount: Int? = null,
        val evenCount: Int? = null,
    )

    fun listPrinters(): List<PrinterInfo>
    fun print(filename: String, fileBytes: ByteArray, options: JSONObject): PrintResult
    fun continueManual(jobId: String, reverseEven: Boolean): PrintResult
    fun cancelManual(jobId: String)
}

class RelayClient(
    private val backend: PrinterBackend,
    private val onStatusChange: (String) -> Unit, // "connected" | "connecting" | "disconnected" | "unconfigured"
    private val onNextReconnectChange: (Long) -> Unit,
    private val onJobRecorded: (name: String, printer: String, ok: Boolean) -> Unit,
) {
    var relayUrl: String = ""
        private set
    var pairingId: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket, no read timeout
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var reconnectDelayMs = 1000L // Start with 1 second
    private val maxReconnectDelayMs = 30000L // Cap at 30 seconds
    private val activeJobs = AtomicInteger(0)
    private var heartbeatAlive = true
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val socket = ws ?: return
            if (!heartbeatAlive) {
                socket.close(1000, "heartbeat timeout")
                return
            }
            heartbeatAlive = false
            socket.send(JSONObject().put("type", "app_ping").toString())
            handler.postDelayed(this, 20000)
        }
    }
    private val periodicReconnectRunnable = object : Runnable {
        override fun run() {
            reconnectWhenIdle()
            handler.postDelayed(this, 5 * 60 * 1000)
        }
    }

    fun configure(relayUrl: String, pairingId: String) {
        this.relayUrl = relayUrl.trimEnd('/')
        this.pairingId = pairingId
        reconnect()
    }

    fun start() {
        if (relayUrl.isBlank()) {
            onStatusChange("unconfigured")
            return
        }
        connect()
        handler.removeCallbacks(periodicReconnectRunnable)
        handler.postDelayed(periodicReconnectRunnable, 5 * 60 * 1000)
        onNextReconnectChange(System.currentTimeMillis() + 5 * 60 * 1000)
    }

    fun reconnect() {
        ws?.close(1000, "reconnecting")
        ws = null
        handler.removeCallbacks(heartbeatRunnable)
        if (relayUrl.isBlank()) {
            onStatusChange("unconfigured")
            return
        }
        connect()
    }

    private fun reconnectWhenIdle() {
        if (relayUrl.isBlank()) return
        if (activeJobs.get() > 0) {
            handler.postDelayed({ reconnectWhenIdle() }, 10000)
            return
        }
        reconnect()
        onNextReconnectChange(System.currentTimeMillis() + 5 * 60 * 1000)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "stopping")
        ws = null
    }

    private fun connect() {
        val httpUrl = relayUrl.replaceFirst(Regex("^http"), "ws") + "/ws"
        onStatusChange("connecting")

        val request = Request.Builder().url(httpUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 1000L // Reset to initial delay on successful connection
                webSocket.send(JSONObject().put("type", "register").put("id", pairingId).toString())
                heartbeatAlive = true
                handler.removeCallbacks(heartbeatRunnable)
                handler.postDelayed(heartbeatRunnable, 20000)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.removeCallbacks(heartbeatRunnable)
                onStatusChange("disconnected")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.removeCallbacks(heartbeatRunnable)
                onStatusChange("disconnected")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        handler.postDelayed({ if (relayUrl.isNotBlank()) connect() }, reconnectDelayMs)
        // Exponential backoff: double the delay, capped at maxReconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(maxReconnectDelayMs)
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    private fun handleMessage(socket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "registered" -> onStatusChange("connected")
            "app_pong" -> heartbeatAlive = true

            "get_printers" -> {
                val requestId = msg.optString("requestId")
                try {
                    val printers = backend.listPrinters()
                    val arr = JSONArray()
                    printers.forEach { p ->
                        arr.put(JSONObject().put("name", p.name).put("paperSizes", JSONArray(p.paperSizes)))
                    }
                    send(JSONObject().put("type", "printers_result").put("requestId", requestId).put("printers", arr))
                } catch (e: Exception) {
                    send(JSONObject().put("type", "printers_result").put("requestId", requestId).put("error", e.message ?: "error"))
                }
            }

            "print" -> {
                val requestId = msg.optString("requestId")
                activeJobs.incrementAndGet()
                try {
                    val filename = msg.optString("filename")
                    val fileBytes = android.util.Base64.decode(msg.optString("fileBase64"), android.util.Base64.DEFAULT)
                    val options = msg.optJSONObject("options") ?: JSONObject()
                    val result = backend.print(filename, fileBytes, options)
                    onJobRecorded(filename, options.optString("printer"), result.ok)
                    send(resultToJson("print_result", requestId, result))
                } catch (e: Exception) {
                    send(
                        JSONObject().put("type", "print_result").put("requestId", requestId)
                            .put("ok", false).put("error", e.message ?: "error")
                    )
                } finally {
                    activeJobs.decrementAndGet()
                }
            }

            "continue_manual" -> {
                val requestId = msg.optString("requestId")
                activeJobs.incrementAndGet()
                try {
                    val jobId = msg.optString("jobId")
                    val reverseEven = msg.optString("reverseEven") != "false"
                    val result = backend.continueManual(jobId, reverseEven)
                    onJobRecorded("(side 2 of 2)", "-", result.ok)
                    send(resultToJson("continue_manual_result", requestId, result))
                } catch (e: Exception) {
                    send(
                        JSONObject().put("type", "continue_manual_result").put("requestId", requestId)
                            .put("ok", false).put("error", e.message ?: "error")
                    )
                } finally {
                    activeJobs.decrementAndGet()
                }
            }

            "cancel_manual" -> {
                val requestId = msg.optString("requestId")
                backend.cancelManual(msg.optString("jobId"))
                send(JSONObject().put("type", "cancel_manual_result").put("requestId", requestId).put("ok", true))
            }
        }
    }

    private fun resultToJson(type: String, requestId: String, r: PrinterBackend.PrintResult): JSONObject {
        val obj = JSONObject().put("type", type).put("requestId", requestId).put("ok", r.ok)
        r.error?.let { obj.put("error", it) }
        r.note?.let { obj.put("note", it) }
        r.method?.let { obj.put("method", it) }
        r.phase?.let { obj.put("phase", it) }
        r.jobId?.let { obj.put("jobId", it) }
        r.totalPages?.let { obj.put("totalPages", it) }
        r.oddCount?.let { obj.put("oddCount", it) }
        r.evenCount?.let { obj.put("evenCount", it) }
        return obj
    }
}
