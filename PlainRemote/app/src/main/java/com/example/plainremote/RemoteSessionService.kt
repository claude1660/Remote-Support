package com.example.plainremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import java.nio.charset.StandardCharsets

/**
 * Runs the whole "internet mode" remote-support session:
 *  1. Turns this phone's screen into a WebRTC video track (MediaProjection).
 *  2. Opens a WebRTC data channel for tap/swipe/back/home commands.
 *  3. Uses [SignalingClient] (Firestore) only to hand off the SDP/ICE
 *     handshake to the technician's browser.
 *  4. Once connected, video + control flow directly device-to-device -
 *     Firebase is no longer involved and the free plan is never at risk
 *     of being exhausted by a long session.
 *
 * Requires the user to have already granted the screen-capture prompt
 * (see MainActivity) - exactly like the local Wi-Fi mode, nothing here
 * can be started by a remote party.
 */
class RemoteSessionService : Service() {

    private lateinit var eglBase: EglBase
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null
    private var signaling: SignalingClient? = null
    private var sessionCode: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        sessionCode = intent?.getStringExtra(EXTRA_SESSION_CODE) ?: SignalingClient.randomCode()

        promoteToForeground()

        if (resultCode != 0 && resultData != null) {
            startSession(resultData)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    private fun promoteToForeground() {
        val channelId = "plainremote_session"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Remote support session", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PlainRemote — session active")
            .setContentText("Code: $sessionCode  •  tap to end in the app")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startSession(resultData: Intent) {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        val pcFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = pcFactory

        // --- Screen capture -> WebRTC video track ---
        val capturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        })
        videoCapturer = capturer
        val videoSource = pcFactory.createVideoSource(true)
        val helper = SurfaceTextureHelper.create("PlainRemoteCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        capturer.initialize(helper, applicationContext, videoSource.capturerObserver)
        val metrics = resources.displayMetrics
        capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 12)
        val videoTrack = pcFactory.createVideoTrack("plainremote_screen", videoSource)

        // --- ICE servers: free public STUN + free Open Relay Project TURN ---
        // TURN is what makes this work across "any network" reliably - STUN
        // alone fails whenever either side is behind carrier-grade/mobile NAT.
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val sig = SignalingClient(sessionCode)
        signaling = sig

        val pc = pcFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sig.sendLocalCandidate(candidate)
            }

            override fun onDataChannel(channel: DataChannel) = attachDataChannel(channel)
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: run {
            stopSelf()
            return
        }
        peerConnection = pc
        pc.addTrack(videoTrack, listOf("plainremote-stream"))
        attachDataChannel(pc.createDataChannel("control", DataChannel.Init()))

        sig.listenForRemoteCandidates { candidate -> pc.addIceCandidate(candidate) }

        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(SdpObserverAdapter(), desc)
                sig.createSession(desc) { answer ->
                    pc.setRemoteDescription(SdpObserverAdapter(), answer)
                }
            }
        }, MediaConstraints())
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleCommand(String(bytes, StandardCharsets.UTF_8))
            }
        })
    }

    /**
     * Commands arrive as small JSON objects over the data channel, with
     * tap/swipe coordinates normalized to 0..1 so they scale correctly
     * regardless of the video resolution the browser negotiated:
     *   {"type":"tap","x":0.42,"y":0.81}
     *   {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"duration":300}
     *   {"type":"back"} / {"type":"home"}
     */
    private fun handleCommand(json: String) {
        val svc = RemoteAccessibilityService.instance ?: return
        try {
            val obj = JSONObject(json)
            val metrics = resources.displayMetrics
            when (obj.optString("type")) {
                "tap" -> svc.tap(
                    (obj.getDouble("x") * metrics.widthPixels).toFloat(),
                    (obj.getDouble("y") * metrics.heightPixels).toFloat()
                )
                "swipe" -> svc.swipe(
                    (obj.getDouble("x1") * metrics.widthPixels).toFloat(),
                    (obj.getDouble("y1") * metrics.heightPixels).toFloat(),
                    (obj.getDouble("x2") * metrics.widthPixels).toFloat(),
                    (obj.getDouble("y2") * metrics.heightPixels).toFloat(),
                    obj.optLong("duration", 250L)
                )
                "back" -> svc.back()
                "home" -> svc.home()
            }
        } catch (_: Exception) {
            // Malformed/unknown command - ignore rather than crash the session.
        }
    }

    private fun teardown() {
        signaling?.close()
        signaling?.deleteSession()
        dataChannel?.close()
        peerConnection?.close()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        factory?.dispose()
        if (::eglBase.isInitialized) eglBase.release()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SESSION_CODE = "sessionCode"
        private const val NOTIF_ID = 42
    }
}

/** SdpObserver with no-op defaults so call sites only override what they need. */
private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
