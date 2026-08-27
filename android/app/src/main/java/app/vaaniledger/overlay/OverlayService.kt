package app.vaaniledger.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * The floating bubble.
 *
 * WEEK 0 SPIKE — this is the make-or-break component. If it will not draw
 * over WhatsApp on the team's actual phones, the product does not exist and
 * the fallback (NotificationListenerService + OCR) has to be planned the same
 * day. Do not move past this until it is verified on the demo device.
 *
 * Runs as a foreground service so it survives app switches. An Activity
 * cannot do this job — the moment the shopkeeper opens WhatsApp, an Activity
 * is gone.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var panel: View? = null

    private val layoutType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showBubble()
        isRunning = true
    }

    // ----------------------------------------------------------------------
    // The bubble
    // ----------------------------------------------------------------------

    private fun showBubble() {
        if (bubble != null) return

        val view = BubbleView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            // NOT_FOCUSABLE keeps WhatsApp's keyboard and scrolling working
            // underneath us. Without it we steal input from the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 320
        }

        view.setOnTouchListener(DragToEdge(params) { expand() })
        windowManager.addView(view, params)
        bubble = view
    }

    /**
     * Drag handling that distinguishes a drag from a tap.
     *
     * Snapping to the nearest edge on release matters more than it sounds:
     * a bubble parked mid-screen covers the message the shopkeeper is trying
     * to read, which is the one thing it must never do.
     */
    private inner class DragToEdge(
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        view.performClick()
                        onTap()
                    } else {
                        snapToEdge(view, params)
                    }
                }

                else -> return false
            }
            return true
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        params.x = if (params.x + view.width / 2 < screenWidth / 2) {
            EDGE_MARGIN
        } else {
            screenWidth - view.width - EDGE_MARGIN
        }
        windowManager.updateViewLayout(view, params)
    }

    // ----------------------------------------------------------------------
    // The mini dashboard
    // ----------------------------------------------------------------------

    /**
     * Expand into the panel: today's three numbers plus the actions.
     *
     * Numbers come straight from Room — never a network call. The panel has
     * to open instantly even with no signal, because that is the situation it
     * will most often open in.
     */
    private fun expand() {
        if (panel != null) return

        val view = PanelView(this, onDismiss = { collapse() }, onCapture = { capture() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            // Focusable here so the panel's own controls take input, but
            // WATCH_OUTSIDE_TOUCH lets us dismiss on an outside tap.
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(view, params)
        panel = view
        bubble?.visibility = View.GONE
    }

    /**
     * "Yeh likh lo" — read whatever chat is on screen behind the panel.
     *
     * Collapses first so the panel is not part of what gets read.
     */
    private fun capture() {
        collapse()
        val service = app.vaaniledger.capture.LedgerAccessibilityService.instance
        if (service == null) {
            toast("Chat padhne ki ijaazat band hai")
            return
        }
        val messages = service.captureVisible()
        toast(
            if (messages.isEmpty()) "Koi message nahi mila"
            else "${messages.size} message mile — engine week 3 mein"
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun collapse() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        bubble?.visibility = View.VISIBLE
    }

    // ----------------------------------------------------------------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VaaniLedger",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Keeps the ledger bubble available" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VaaniLedger chalu hai")
            .setContentText("Bubble dabaye, hisaab likhne ke liye")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubble?.let { windowManager.removeView(it) }
        panel?.let { windowManager.removeView(it) }
        bubble = null
        panel = null
        isRunning = false
    }

    companion object {
        /** Read by MainActivity to label its start/stop button. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "vaani_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val TAP_SLOP = 12
        private const val EDGE_MARGIN = 16

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
