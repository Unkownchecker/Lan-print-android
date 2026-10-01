package com.lanprint.android

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

object XqxPrinter {

    data class PrintOptions(
        val paperSize: String = DEFAULT_PAPER_SIZE,
        val orientationLandscape: Boolean = false,
        val copies: Int = 1,
    )

    fun pdfPageCount(pdfPath: String): Int {
        val pfd = ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(pfd).use { it.pageCount }
        } finally {
            pfd.close()
        }
    }

    /**
     * Renders [pageNumbers] (1-based) of the PDF at [pdfPath], converts to
     * XQX, and sends it to [printer]. Returns null on success, or an error
     * message on failure.
     */
    fun printPages(
        context: Context,
        usbManager: UsbPrinterManager,
        printer: UsbPrinterManager.ConnectedPrinter,
        pdfPath: String,
        pageNumbers: List<Int>,
        options: PrintOptions,
    ): String? {
        val geometry = PAPER_SIZES[options.paperSize.lowercase()] ?: PAPER_SIZES[DEFAULT_PAPER_SIZE]
            ?: return "Invalid paper size configuration"

        val workDir = File(context.cacheDir, "xqx-work").apply { mkdirs() }
        val pbmFile = File(workDir, "job-${System.currentTimeMillis()}.pbm")
        val xqxFile = File(workDir, "job-${System.currentTimeMillis()}.xqx")

        try {
            PdfRasterizer.renderPagesToPbm(
                pdfPath, pageNumbers, geometry, options.orientationLandscape, pbmFile.absolutePath
            )

            val args = arrayOf(
                "-r1200x600",
                "-g${geometry.widthPx}x${geometry.heightPx}",
                "-p${geometry.paperCode}",
                "-m1", // standard plain paper
                "-n${options.copies.coerceAtLeast(1)}",
                "-d1", // this printer family has no hardware duplexer over USB -- always single-sided per pass; two-sided is handled at the app level (print odd pages, then even pages)
                "-s7", // auto tray
                "-u${geometry.clipUlx}x${geometry.clipUly}",
                "-l${geometry.clipLrx}x${geometry.clipLry}",
                "-L3",
                "-T3",
            )

            val rc = Foo2xqx.nativeConvert(pbmFile.absolutePath, xqxFile.absolutePath, args)
            if (rc != 0) return PrintError.ConversionFailed(rc).userFriendlyMessage

            val xqxBytes = xqxFile.readBytes()
            if (xqxBytes.isEmpty()) return PrintError.ConversionProducedNoData().userFriendlyMessage

            val ok = usbManager.sendBulkData(printer, xqxBytes)
            if (!ok) return PrintError.UsbTransferFailed().userFriendlyMessage

            return null
        } catch (e: Exception) {
            return PrintError.GeneralError(e.message ?: "Unknown print error").userFriendlyMessage
        } finally {
            pbmFile.delete()
            xqxFile.delete()
        }
    }
}
