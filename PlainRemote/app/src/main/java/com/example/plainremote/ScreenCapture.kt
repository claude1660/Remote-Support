package com.example.plainremote

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Grabs periodic screenshots from the MediaProjection the user
 * approved, so they can be served as a simple MJPEG stream to a
 * browser on the PC. This is a "poll a screenshot" approach rather
 * than true low-latency video - simple, dependency-free, and good
 * enough for viewing/clicking around a phone UI remotely.
 */
class ScreenCapture(
    private val mediaProjection: MediaProjection,
    windowManager: WindowManager
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    private val width: Int
    private val height: Int
    private val density: Int

    @Volatile
    var latestJpeg: ByteArray? = null
        private set

    init {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        // Downscale a bit to keep frames small and the stream responsive.
        width = metrics.widthPixels / 2
        height = metrics.heightPixels / 2
        density = metrics.densityDpi
    }

    fun start() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PlainRemoteCapture",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                latestJpeg = out.toByteArray()
                bitmap.recycle()
            } catch (_: Exception) {
                // Drop malformed frames silently; the next one will arrive shortly.
            } finally {
                image.close()
            }
        }, handler)
    }

    fun stop() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection.stop()
    }
}
