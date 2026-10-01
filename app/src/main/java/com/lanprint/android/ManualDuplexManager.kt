package com.lanprint.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual duplex: print all odd pages, wait for whoever's at the printer to
 * flip the stack and reload it, then print the even pages (reversed order
 * by default). Mirrors the desktop app's server/manual-duplex.js so the
 * phone app's "Sides" option and flip-prompt UI work identically against
 * either backend.
 */
class ManualDuplexManager(private val context: Context) {

    data class Job(
        val pdfPath: String,
        val printerModel: PrinterModel,
        val options: XqxPrinter.PrintOptions,
        val totalPages: Int,
        val expiryRunnable: Runnable,
    )

    companion object {
        private val JOB_EXPIRY_MS = 20 * 60 * 1000L // 20 minutes, matching the desktop app
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val handler = Handler(Looper.getMainLooper())

    data class StartResult(
        val jobId: String,
        val totalPages: Int,
        val oddCount: Int,
        val evenCount: Int,
        val oddPages: List<Int>,
    )

    fun oddPages(total: Int): List<Int> = (1..total step 2).toList()
    fun evenPages(total: Int, reverse: Boolean): List<Int> {
        val list = (2..total step 2).toList()
        return if (reverse) list.reversed() else list
    }

    /** Copies [sourcePdfPath] into app storage (so it survives past this call) and returns job info. */
    fun start(sourcePdfPath: String, printerModel: PrinterModel, options: XqxPrinter.PrintOptions): StartResult? {
        val sourceFile = File(sourcePdfPath)
        if (!sourceFile.exists()) {
            return null
        }

        val totalPages = XqxPrinter.pdfPageCount(sourcePdfPath)
        if (totalPages < 2) return null

        val jobId = UUID.randomUUID().toString()
        val workDir = File(context.cacheDir, "manual-duplex").apply { mkdirs() }
        val storedPdf = File(workDir, "$jobId.pdf")
        try {
            sourceFile.copyTo(storedPdf, overwrite = true)
        } catch (e: Exception) {
            return null
        }

        val expiryRunnable = Runnable { cancel(jobId) }
        handler.postDelayed(expiryRunnable, JOB_EXPIRY_MS)

        jobs[jobId] = Job(storedPdf.absolutePath, printerModel, options, totalPages, expiryRunnable)

        val odd = oddPages(totalPages)
        return StartResult(jobId, totalPages, odd.size, totalPages - odd.size, odd)
    }

    fun get(jobId: String): Job? = jobs[jobId]

    fun cancel(jobId: String) {
        val job = jobs.remove(jobId) ?: return
        handler.removeCallbacks(job.expiryRunnable)
        File(job.pdfPath).delete()
    }

    fun finish(jobId: String) {
        // Same as cancel, but named separately for call-site clarity after a successful phase 2.
        cancel(jobId)
    }
}
