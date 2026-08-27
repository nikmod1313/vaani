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
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.ShopEntity
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.ApiClient
import app.vaaniledger.net.Confidence
import app.vaaniledger.net.ExtractRequest
import app.vaaniledger.net.Extraction
import app.vaaniledger.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var confirmSheet: View? = null

    // SupervisorJob so one failed extraction cannot cancel the scope and
    // silently kill every capture after it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * "Yeh likh lo" — the core loop.
     *
     * read the chat -> extract -> confirm -> save.
     *
     * Extraction goes to the server because that is where the rules engine
     * and the model layer live. If the call fails for any reason, we do NOT
     * lose the capture: the raw text still reaches the confirm sheet with
     * empty fields, so the shopkeeper can enter it by hand. Losing an entry
     * is worse than asking him to type one.
     */
    private fun capture() {
        collapse()

        val service = app.vaaniledger.capture.LedgerAccessibilityService.instance
        if (service == null) {
            android.util.Log.w(TAG, "capture: accessibility service not connected")
            toast("Chat padhne ki ijaazat band hai")
            return
        }

        // Let our own panel leave the screen first — reading the window while
        // it is still up captures the panel, not the chat underneath it.
        android.os.Handler(mainLooper).postDelayed({
            val messages = service.captureVisible()
            android.util.Log.i(TAG, "capture: ${messages.size} message(s)")
            if (messages.isEmpty()) {
                toast("Koi message nahi mila")
                return@postDelayed
            }
            extractAndConfirm(messages, service.currentChatTitle())
        }, PANEL_SETTLE_MS)
    }

    private fun extractAndConfirm(
        messages: List<app.vaaniledger.capture.LedgerAccessibilityService.CapturedMessage>,
        senderName: String?,
    ) {
        // Incoming messages only: what the shopkeeper sent is not an order
        // placed with him. Consecutive ones join, because an order routinely
        // arrives across three messages and means nothing split apart.
        val text = messages.filter { !it.isOutgoing }
            .takeLast(MAX_MESSAGES_PER_CAPTURE)
            .joinToString(" ") { it.text }
            .trim()

        if (text.isBlank()) {
            toast("Koi order nahi mila")
            return
        }

        scope.launch {
            val db = LedgerDatabase.get(this@OverlayService)
            val known = runCatching { db.customers().names(SHOP_ID) }.getOrDefault(emptyList())

            val extraction = runCatching {
                ApiClient.api.extract(
                    ExtractRequest(
                        text = text,
                        senderName = senderName,
                        knownCustomers = known,
                        shopId = SHOP_ID,
                        source = "chat",
                    )
                )
            }.onFailure {
                android.util.Log.w(TAG, "extract failed: ${it.message}")
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (extraction == null) {
                    // Degrade, never drop. The text survives into the sheet.
                    toast("Server nahi mila — haath se bhariye")
                    showConfirm(blankExtraction(text))
                } else {
                    showConfirm(extraction)
                }
            }
        }
    }

    /** An empty result carrying the captured text, for the offline path. */
    private fun blankExtraction(text: String) = Extraction(
        text = text,
        confidence = Confidence(overall = 0.0, route = "manual"),
    )

    private fun showConfirm(extraction: Extraction) {
        if (confirmSheet != null) return

        val view = ConfirmSheetView(
            context = this,
            extraction = extraction,
            onConfirm = { save(extraction) },
            onEdit = { dismissConfirm(); toast("Edit screen week 3 mein") },
            onDismiss = { dismissConfirm() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        windowManager.addView(view, params)
        confirmSheet = view
        bubble?.visibility = View.GONE
    }

    private fun dismissConfirm() {
        confirmSheet?.let { windowManager.removeView(it) }
        confirmSheet = null
        bubble?.visibility = View.VISIBLE
    }

    /**
     * "Sahi hai" — write it to Room, then ask for a sync.
     *
     * Room first and always. Sync is an optimisation; if it never runs the
     * entry is still safely on the phone, which is the whole offline promise.
     */
    private fun save(extraction: Extraction) {
        dismissConfirm()

        scope.launch {
            val db = LedgerDatabase.get(this@OverlayService)
            runCatching {
                db.shops().upsert(ShopEntity(id = SHOP_ID, name = SHOP_NAME))

                val customer = extraction.customer?.takeIf { it.isNotBlank() }
                    ?.let { db.customers().resolve(SHOP_ID, it) }

                db.transactions().upsert(
                    TransactionEntity(
                        shopId = SHOP_ID,
                        customerId = customer?.id,
                        customerName = customer?.name,
                        amount = extraction.amount ?: 0.0,
                        mode = extraction.mode.takeIf { it != "unknown" } ?: "udhaar",
                        direction = extraction.direction.takeIf { it != "unknown" } ?: "sale",
                        items = extraction.items.joinToString(","),
                        source = "chat",
                        confidence = extraction.confidence.overall,
                    )
                )
            }.onFailure {
                android.util.Log.e(TAG, "save failed", it)
            }

            val pending = runCatching { db.transactions().pending().size }.getOrDefault(0)

            withContext(Dispatchers.Main) {
                val amount = extraction.amount?.let { "₹%,.0f".format(it) } ?: "entry"
                toast("$amount saved · $pending pending sync")
            }
            SyncWorker.syncNow(this@OverlayService, SHOP_ID, SHOP_NAME)
        }
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
        confirmSheet?.let { windowManager.removeView(it) }
        bubble = null
        panel = null
        confirmSheet = null
        isRunning = false
        scope.cancel()
    }

    companion object {
        /** Read by MainActivity to label its start/stop button. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "VaaniCapture"
        /** Long enough for our own panel to be gone before we read the window. */
        private const val PANEL_SETTLE_MS = 350L
        /** Enough for a three-message order, not the whole scrollback. */
        private const val MAX_MESSAGES_PER_CAPTURE = 6
        // TODO(week 3): from onboarding, once a shop can be created in-app.
        private const val SHOP_ID = "sharma-kirana"
        private const val SHOP_NAME = "Sharma Kirana Store"
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
