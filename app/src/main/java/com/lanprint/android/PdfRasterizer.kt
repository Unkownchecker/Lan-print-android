package com.lanprint.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

object PdfRasterizer {

    // Printer target pages are large (e.g. 10200x6600 px for Letter at this
    // printer's native 1200x600 dpi). Rendering + dithering a bitmap that
    // size directly (ARGB_8888 = 4 bytes/px) would need ~270MB just for one
    // page and will OutOfMemory on real phones. Instead we render at a
    // capped, safe intermediate resolution, then nearest-neighbor upsample
    // row-by-row while dithering straight to the output file -- the target
    // pixel grid is still produced at full size, we just never hold the
    // whole thing in memory at once. Quality loss from this is negligible
    // for a monochrome laser printer's actual resolvable detail.
    private const val MAX_INTERMEDIATE_DIM = 2400

    /**
     * Renders [pageNumbers] (1-based) of the PDF at [pdfPath] into a single
     * file at [outPbmPath] containing one concatenated raw-PBM (P4) block
     * per page, fitted to [geometry] (rotated if [orientationLandscape]).
     */
    fun renderPagesToPbm(
        pdfPath: String,
        pageNumbers: List<Int>,
        geometry: PaperGeometry,
        orientationLandscape: Boolean,
        outPbmPath: String,
    ) {
        val pdfFile = File(pdfPath)
        if (!pdfFile.exists()) {
            throw IllegalArgumentException("PDF file does not exist: $pdfPath")
        }

        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val out = RandomAccessFile(outPbmPath, "rw")
        out.setLength(0)

        val targetW = if (orientationLandscape) geometry.heightPx else geometry.widthPx
        val targetH = if (orientationLandscape) geometry.widthPx else geometry.heightPx

        val scale = min(1.0, MAX_INTERMEDIATE_DIM.toDouble() / max(targetW, targetH))
        val workingW = max(1, (targetW * scale).toInt())
        val workingH = max(1, (targetH * scale).toInt())

        try {
            for (pageNum in pageNumbers) {
                val index = pageNum - 1
                if (index < 0 || index >= renderer.pageCount) continue
                val page = renderer.openPage(index)
                try {
                    val bitmap = Bitmap.createBitmap(workingW, workingH, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)

                        val pageRatio = page.width.toFloat() / page.height.toFloat()
                        val boxRatio = workingW.toFloat() / workingH.toFloat()
                        val drawW: Int
                        val drawH: Int
                        if (pageRatio > boxRatio) {
                            drawW = workingW
                            drawH = (workingW / pageRatio).toInt().coerceAtLeast(1)
                        } else {
                            drawH = workingH
                            drawW = (workingH * pageRatio).toInt().coerceAtLeast(1)
                        }
                        val left = (workingW - drawW) / 2
                        val top = (workingH - drawH) / 2
                        val destRect = Rect(left, top, left + drawW, top + drawH)

                        page.render(bitmap, destRect, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        writeDitheredPbmPage(out, bitmap, workingW, workingH, targetW, targetH)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer.close()
            pfd.close()
            out.close()
        }
    }

    /**
     * Streams a [targetW]x[targetH] raw-PBM (P4) page from a smaller
     * [srcW]x[srcH] source bitmap: nearest-neighbor upsamples row by row and
     * applies Floyd-Steinberg dithering using only two row-sized error
     * buffers (not a whole-page buffer), so memory use is O(width), not
     * O(width*height), regardless of how large the target page is.
     *
     * PBM (P4) convention: 1 bit per pixel, MSB first, each row padded to a
     * whole byte, BLACK = bit 1, WHITE = bit 0 (verified against libnetpbm's
     * own convention and against real foo2xqx output -- do not invert this).
     */
    private fun writeDitheredPbmPage(
        out: RandomAccessFile,
        src: Bitmap,
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
    ) {
        out.write("P4\n$targetW $targetH\n".toByteArray(Charsets.US_ASCII))

        val rowBytes = (targetW + 7) / 8
        val rowBuf = ByteArray(rowBytes)

        var curErr = FloatArray(targetW)
        var nextErr = FloatArray(targetW)

        val srcRowPixels = IntArray(srcW)
        val srcRowLum = FloatArray(srcW)
        var loadedSrcRow = -1

        for (y in 0 until targetH) {
            val sy = (y.toLong() * srcH / targetH).toInt().coerceIn(0, srcH - 1)
            if (sy != loadedSrcRow) {
                src.getPixels(srcRowPixels, 0, srcW, 0, sy, srcW, 1)
                for (sx in 0 until srcW) {
                    val p = srcRowPixels[sx]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    srcRowLum[sx] = 0.299f * r + 0.587f * g + 0.114f * b
                }
                loadedSrcRow = sy
            }

            rowBuf.fill(0)
            nextErr.fill(0f)

            for (x in 0 until targetW) {
                val sx = (x.toLong() * srcW / targetW).toInt().coerceIn(0, srcW - 1)
                val old = srcRowLum[sx] + curErr[x]
                val isBlack = old < 128f
                val newVal = if (isBlack) 0f else 255f
                val err = old - newVal

                if (isBlack) {
                    rowBuf[x / 8] = (rowBuf[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }

                if (x + 1 < targetW) curErr[x + 1] += err * 7f / 16f
                if (x > 0) nextErr[x - 1] += err * 3f / 16f
                nextErr[x] += err * 5f / 16f
                if (x + 1 < targetW) nextErr[x + 1] += err * 1f / 16f
            }

            out.write(rowBuf)
            val tmp = curErr
            curErr = nextErr
            nextErr = tmp
        }
    }
}
