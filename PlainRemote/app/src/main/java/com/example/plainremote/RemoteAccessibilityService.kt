package com.example.plainremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Executes the actual on-screen taps/swipes that arrive as commands
 * from the PC web UI. This service does nothing by itself - it only
 * acts when WebServerService calls its companion instance, which only
 * happens in response to a request hitting the local HTTP server that
 * WebServerService runs on THIS device.
 *
 * Android will only let a user turn this service on manually from
 * Settings > Accessibility, and it can be turned off there at any time.
 */
class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun home() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }
}
