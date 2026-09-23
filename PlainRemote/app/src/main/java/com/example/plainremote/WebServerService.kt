package com.example.plainremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Foreground service that:
 *  1. Runs a tiny embedded HTTP server other devices on the same
 *     Wi-Fi network can reach at http://<phone-ip>:8080
 *  2. Serves a file browser rooted at external storage
 *  3. Serves a live (polled) screenshot stream from MediaProjection
 *  4. Accepts tap/swipe/back/home commands and forwards them to
 *     RemoteAccessibilityService, which is what actually moves the UI
 *
 * Nothing here can be started remotely - it only ever runs after the
 * device owner flips the switch in MainActivity on this same phone.
 */
class WebServerService : Service() {

    private var server: LocalHttpServer? = null
    private var screenCapture: ScreenCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != 0 && resultData != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            screenCapture = ScreenCapture(projection, windowManager).also { it.start() }
        }

        server = LocalHttpServer(screenCapture)
        server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        screenCapture?.stop()
    }

    private fun buildNotification(): android.app.Notification {
        val channelId = "plainremote_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "PlainRemote service", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("PlainRemote is running")
            .setContentText("Accessible from your PC's browser on the same Wi-Fi network")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
}

/** The actual HTTP request handling lives here, kept separate for clarity. */
private class LocalHttpServer(
    private val screenCapture: ScreenCapture?
) : NanoHTTPD(8080) {

    private val rootDir: File = Environment.getExternalStorageDirectory()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters

        return try {
            when {
                uri == "/" -> htmlResponse(INDEX_PAGE)
                uri == "/remote" -> htmlResponse(REMOTE_PAGE)
                uri == "/files" -> serveFileListing(params["path"]?.firstOrNull() ?: "")
                uri == "/download" -> serveFileDownload(params["path"]?.firstOrNull() ?: "")
                uri == "/stream" -> serveMjpegSnapshot()
                uri == "/tap" -> {
                    val x = params["x"]?.firstOrNull()?.toFloatOrNull()
                    val y = params["y"]?.firstOrNull()?.toFloatOrNull()
                    if (x != null && y != null) RemoteAccessibilityService.instance?.tap(x, y)
                    plainText("ok")
                }
                uri == "/swipe" -> {
                    val x1 = params["x1"]?.firstOrNull()?.toFloatOrNull()
                    val y1 = params["y1"]?.firstOrNull()?.toFloatOrNull()
                    val x2 = params["x2"]?.firstOrNull()?.toFloatOrNull()
                    val y2 = params["y2"]?.firstOrNull()?.toFloatOrNull()
                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                        RemoteAccessibilityService.instance?.swipe(x1, y1, x2, y2, 200)
                    }
                    plainText("ok")
                }
                uri == "/key" -> {
                    when (params["action"]?.firstOrNull()) {
                        "back" -> RemoteAccessibilityService.instance?.back()
                        "home" -> RemoteAccessibilityService.instance?.home()
                    }
                    plainText("ok")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}"
            )
        }
    }

    private fun htmlResponse(html: String) =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)

    private fun plainText(text: String) =
        newFixedLengthResponse(Response.Status.OK, "text/plain", text)

    /** Lists files/folders under rootDir/relativePath as simple clickable links. */
    private fun serveFileListing(relativePath: String): Response {
        val target = File(rootDir, relativePath).canonicalFile
        if (!target.path.startsWith(rootDir.canonicalPath)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        if (!target.exists() || !target.isDirectory) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val entries = target.listFiles()?.sortedBy { it.name } ?: emptyList()
        val rows = entries.joinToString("\n") { f ->
            val childPath = File(relativePath, f.name).path
            if (f.isDirectory) {
                "<li>📁 <a href=\"/files?path=${enc(childPath)}\">${f.name}</a></li>"
            } else {
                "<li>📄 ${f.name} - <a href=\"/download?path=${enc(childPath)}\">download</a></li>"
            }
        }
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Files</title></head>
            <body style="font-family:sans-serif;padding:16px;">
            <h2>📂 /${relativePath}</h2>
            <p><a href="/">← Home</a></p>
            <ul style="list-style:none;padding:0;line-height:2;">$rows</ul>
            </body></html>
        """.trimIndent()
        return htmlResponse(html)
    }

    private fun serveFileDownload(relativePath: String): Response {
        val target = File(rootDir, relativePath).canonicalFile
        if (!target.path.startsWith(rootDir.canonicalPath) || !target.isFile) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val stream = FileInputStream(target)
        val mime = when (target.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, stream, target.length())
    }

    /** Returns the single most recent JPEG frame; the remote page polls this in a loop. */
    private fun serveMjpegSnapshot(): Response {
        val jpeg = screenCapture?.latestJpeg
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE, "text/plain",
                "Screen capture not available"
            )
        val stream = java.io.ByteArrayInputStream(jpeg)
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", stream, jpeg.size.toLong())
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val INDEX_PAGE = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PlainRemote</title></head>
            <body style="font-family:sans-serif;padding:24px;">
            <h1>PlainRemote</h1>
            <p><a href="/files">📂 Browse files</a></p>
            <p><a href="/remote">🖥️ Screen + remote control</a></p>
            </body></html>
        """
        private const val REMOTE_PAGE = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Remote control</title></head>
            <body style="font-family:sans-serif;padding:16px;text-align:center;">
            <p><a href="/">← Home</a></p>
            <p>Click on the image below to tap the corresponding spot on the phone.</p>
            <img id="scr" src="/stream" style="max-width:100%;border:1px solid #ccc;cursor:crosshair;">
            <br><br>
            <button onclick="cmd('back')">⬅ Back</button>
            <button onclick="cmd('home')">🏠 Home</button>
            <script>
              const img = document.getElementById('scr');
              function refresh() { img.src = '/stream?t=' + Date.now(); }
              setInterval(refresh, 600);
              img.addEventListener('click', (e) => {
                const rect = img.getBoundingClientRect();
                const scaleX = img.naturalWidth / rect.width;
                const scaleY = img.naturalHeight / rect.height;
                const x = (e.clientX - rect.left) * scaleX * 2; // *2: capture is downscaled
                const y = (e.clientY - rect.top) * scaleY * 2;
                fetch(`/tap?x=${'$'}{x}&y=${'$'}{y}`);
              });
              function cmd(action) { fetch(`/key?action=${'$'}{action}`); }
            </script>
            </body></html>
        """
    }
}
