package dev.voicecomposer.flow

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.voicecomposer.R
import dev.voicecomposer.VoiceComposerApp
import dev.voicecomposer.security.SafeLog
import dev.voicecomposer.ui.ComposerActivity
import kotlin.math.abs

/**
 * Hosts the floating microphone bubble.
 *
 * Original minimalist design: a small circular handle when idle, expanding when
 * active. It is draggable, snaps to whichever edge the user last left it on,
 * and is hidden entirely whenever [FlowAccessibilityService] reports an unsafe
 * or non-editable context.
 *
 * The bubble never displays draft text. Tapping it opens the composer, which is
 * where the draft, the preview and the commit buttons live - so a shoulder
 * surfer looking at the bubble learns nothing, and the approval gate stays in
 * one place.
 *
 * NOTE: implemented but not device-tested. Overlay behaviour across rotation,
 * keyboard show/hide and multi-window could not be exercised without a device;
 * see docs/TEST_RESULTS.md.
 */
class BubbleOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubble()
            else -> hideBubble()
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) {
            // The user revoked the overlay permission. Fail quietly rather than
            // crashing, and leave Safe Mode fully functional.
            SafeLog.warn(TAG, "overlay_permission_missing")
            stopSelf()
            return
        }

        val view = createBubbleView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE keeps the keyboard in the host app: taking focus
            // would dismiss the IME and defeat the point of the bubble.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = loadX()
            y = loadY()
        }

        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                bubble = view
                layoutParams = params
            }
            .onFailure { SafeLog.failure(TAG, "bubble_add_failed", it) }
    }

    private fun createBubbleView(): View = FrameLayout(this).apply {
        addView(
            TextView(this@BubbleOverlayService).apply {
                text = "◉"
                textSize = 26f
                setPadding(28, 20, 28, 20)
            },
        )
        setOnTouchListener(DragToMoveListener())
    }

    /** Drag to reposition; a tap that did not move opens the composer. */
    private inner class DragToMoveListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        openComposer()
                    } else {
                        persistPosition(params.x, params.y)
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun openComposer() {
        startActivity(
            Intent(this, ComposerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun hideBubble() {
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        layoutParams = null
    }

    private fun persistPosition(x: Int, y: Int) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    private fun loadX(): Int =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_X, 0)

    private fun loadY(): Int =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_Y, 400)

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, VoiceComposerApp.CHANNEL_RECORDING)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text))
            .setSmallIcon(R.drawable.ic_tile_mic)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        hideBubble()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Bubble"
        private const val NOTIFICATION_ID = 42
        private const val TOUCH_SLOP = 12
        private const val PREFS = "bubble"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        const val ACTION_SHOW = "dev.voicecomposer.flow.SHOW_BUBBLE"
        const val ACTION_HIDE = "dev.voicecomposer.flow.HIDE_BUBBLE"

        fun setVisible(context: Context, visible: Boolean) {
            val intent = Intent(context, BubbleOverlayService::class.java).apply {
                action = if (visible) ACTION_SHOW else ACTION_HIDE
            }
            runCatching {
                if (visible) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
