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
import app.vaaniledger.data.Aging
import app.vaaniledger.capture.ReviewQueue
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.extract.OfflineExtractor
import app.vaaniledger.data.ShopEntity
import app.vaaniledger.data.TransactionEntity
import app.vaaniledger.net.ApiClient
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
    private var khataSheet: View? = null
    private var editSheet: View? = null

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Voice transcripts arrive here from VoiceActivity and join the same
        // extract -> confirm -> save path as a chat capture.
        if (intent?.action == ACTION_EXTRACT_TEXT) {
            intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                android.util.Log.i(TAG, "voice: $it")
                extractText(it, senderName = null)
            }
        }
        return START_STICKY
    }

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
        // One sheet at a time. Tapping the bubble while a confirm sheet is up
        // stacked the panel on top of it, leaving two half-visible sheets and
        // no way to tell which buttons belonged to which.
        if (confirmSheet != null) { dismissConfirm(); return }
        if (khataSheet != null) { dismissKhata(); return }
        if (editSheet != null) { dismissEdit(); return }

        // Read today's figures from Room before drawing anything. Room, never
        // the network: this has to open instantly on a counter with no signal,
        // which is where it will most often open.
        scope.launch {
            val totals = runCatching { loadTodayTotals() }
                .getOrDefault(PanelView.Totals())
            withContext(Dispatchers.Main) { showPanel(totals) }
        }
    }

    /**
     * Today, in the device's own timezone.
     *
     * Deliberately not "the last 24 hours" — a shopkeeper's "aaj ka hisaab"
     * means since this morning, and a rolling window would keep yesterday
     * evening's sales in today's total.
     */
    private suspend fun loadTodayTotals(): PanelView.Totals {
        val db = LedgerDatabase.get(this)
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = start + java.util.concurrent.TimeUnit.DAYS.toMillis(1) - 1

        val totals = db.transactions().dayTotalsOnce(SHOP_ID, start, end)
        return PanelView.Totals(
            sale = totals.sale,
            cashIn = totals.cashIn,
            udhaar = totals.udhaar,
            entries = totals.entries,
            pendingSync = db.transactions().pendingCountOnce(),
        )
    }

    private fun showPanel(totals: PanelView.Totals) {
        if (panel != null) return

        val view = PanelView(
            context = this,
            today = totals,
            strings = app.vaaniledger.data.Strings.of(ShopSettings.language(this)).panel,
            onDismiss = { collapse() },
            onCapture = { capture() },
            onVoice = { startVoice() },
            onKhata = { showKhata() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        windowManager.addView(view, params)
        panel = view
        bubble?.visibility = View.GONE
    }

    /**
     * "Bol ke likho" — hand off to the voice Activity.
     *
     * A Service cannot request RECORD_AUDIO or receive a speech result, so
     * this launches a transparent Activity which posts the transcript back.
     */
    private fun startVoice() {
        collapse()
        startActivity(
            android.content.Intent(this, app.vaaniledger.VoiceActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** "Khata" — balances and every entry behind them. */
    private fun showKhata() {
        collapse()
        if (khataSheet != null) return

        scope.launch {
            val db = LedgerDatabase.get(this@OverlayService)
            val all = runCatching { db.transactions().allOnce(SHOP_ID) }.getOrDefault(emptyList())
            val names = runCatching { db.transactions().customersOnce(SHOP_ID) }
                .getOrDefault(emptyList()).associate { it.id to it.name }
            val recent = runCatching { db.transactions().recentOnce(SHOP_ID) }
                .getOrDefault(emptyList())
            val balances = Aging.balances(all, names)
            val phones = runCatching { db.transactions().customersOnce(SHOP_ID) }
                .getOrDefault(emptyList()).associate { it.id to it.phone }

            withContext(Dispatchers.Main) {
                if (khataSheet != null) return@withContext
                val view = KhataView(
                    context = this@OverlayService,
                    balances = balances,
                    recent = recent,
                    onDismiss = { dismissKhata() },
                    onRemind = { balance -> sendReminder(balance, phones[balance.id]) },
                )
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.BOTTOM }
                windowManager.addView(view, params)
                khataSheet = view
                bubble?.visibility = View.GONE
            }
        }
    }

    private fun dismissKhata() {
        khataSheet?.let { windowManager.removeView(it) }
        khataSheet = null
        bubble?.visibility = View.VISIBLE
    }

    /**
     * "Yaad dilao" — the assisted reminder.
     *
     * Composes the message with the amount and opens WhatsApp pre-filled;
     * the shopkeeper taps send himself. This is the design that replaced the
     * WhatsApp Business API: it needs no approval, works with any customer
     * today, and no message about owed money ever leaves the phone without a
     * human seeing it first.
     *
     * If WhatsApp is not installed (this emulator), fall back to the system
     * share sheet so the draft still goes somewhere useful.
     */
    private fun sendReminder(balance: app.vaaniledger.data.CustomerBalance, phone: String?) {
        dismissKhata()

        val intent = app.vaaniledger.reminder.ReminderLinks.reminderIntent(
            phone = phone,
            customerName = balance.name,
            shopName = SHOP_NAME,
            amount = balance.outstanding,
            payeeVpa = null,   // TODO(onboarding): the shop's UPI id
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

        // Log the composed draft. Android elides long URIs in logs, so the
        // intent alone does not show what the customer would actually read.
        android.util.Log.i(
            TAG,
            "reminder to ${balance.name}: " +
                app.vaaniledger.reminder.ReminderLinks.composeMessage(
                    balance.name, SHOP_NAME, balance.outstanding,
                ),
        )

        runCatching { startActivity(intent) }
            .recoverCatching {
                startActivity(
                    android.content.Intent.createChooser(
                        app.vaaniledger.reminder.ReminderLinks.untargetedFallback(intent),
                        "Reminder bhejein",
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            .onFailure {
                android.util.Log.e(TAG, "reminder intent failed", it)
                toast("Koi messaging app nahi mili")
            }
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
            toast(captureStrings().accessibilityOff)
            return
        }

        // Let our own panel leave the screen first — reading the window while
        // it is still up captures the panel, not the chat underneath it.
        android.os.Handler(mainLooper).postDelayed({
            if (!service.hasChatWindow()) {
                android.util.Log.i(TAG, "capture: no chat window on screen")
                toast(captureStrings().openWhatsAppFirst)
                return@postDelayed
            }

            val chatTitle = service.currentChatTitle()
            toast(captureStrings().reading)

            // Scroll back through the conversation rather than reading only
            // what happens to be on screen. An order routinely spans an
            // enquiry, a reply and a commitment, and on a phone-sized screen
            // those three rarely fit together -- which is exactly the case
            // single-screen capture used to get wrong.
            service.harvest(maxScrolls = HARVEST_SCROLLS) { messages ->
                android.util.Log.i(TAG, "capture: ${messages.size} message(s)")
                if (messages.isEmpty()) {
                    toast(captureStrings().noMessagesFound)
                } else {
                    screenAndReview(messages, chatTitle)
                }
            }
        }, PANEL_SETTLE_MS)
    }

    /**
     * Send a harvested chat through the conversation lane.
     *
     * Falls back to the single-message path when the lane resolves nothing.
     * That is not a formality: the resolver deliberately refuses to invent an
     * order out of a conversation that never committed to one, and a
     * shopkeeper who pressed "Yeh likh lo" on a plain "250 ka udhaar likh
     * lena" still expects a confirm sheet.
     */
    private fun screenAndReview(
        messages: List<app.vaaniledger.capture.LedgerAccessibilityService.CapturedMessage>,
        chatTitle: String?,
    ) {
        val customer = chatTitle?.takeIf { it.isNotBlank() }
        if (customer == null) {
            // Without a name there is no way to say WHOSE khata this belongs
            // to, and the conversation lane cannot tell the two sides apart.
            extractAndConfirm(messages, null)
            return
        }

        val chatId = ReviewQueue.chatIdFor(customer)

        scope.launch {
            val db = LedgerDatabase.get(this@OverlayService)
            val category = ShopSettings.category(this@OverlayService)
            val known = runCatching { db.customers().names(SHOP_ID) }.getOrDefault(emptyList())

            // Thread and resolve ON THE PHONE. The messages already carry
            // which side sent each bubble, so there is nothing to parse --
            // only to thread and resolve, and both now run here. No network
            // is touched, which is what lets a shop counter with no signal
            // read an order stated across five messages.
            //
            // Both sides go in. The shopkeeper's own replies carry the quoted
            // rate, and dropping them was why a price stated by the shop
            // never reached the entry it belonged to.
            val seen = db.cursors().byChat(chatId)?.hashes ?: emptySet()
            val turns = messages
                .filter { it.hash !in seen }
                .map {
                    app.vaaniledger.extract.ConversationResolver.Turn(
                        sender = if (it.isOutgoing) SHOP_NAME else customer,
                        body = it.text,
                        // A bubble carries a time label, not a date. Null keeps
                        // send order, which is what the resolver needs.
                        timestamp = null,
                        isShopkeeper = it.isOutgoing,
                        contentHash = it.hash,
                    )
                }

            val orders = app.vaaniledger.extract.ConversationResolver
                .resolveAll(turns, customer, category)
                .map {
                    with(app.vaaniledger.extract.ConversationResolver) {
                        it.toExtraction(known, category)
                    }
                }
            android.util.Log.i(TAG, "screen: ${turns.size} new turn(s), ${orders.size} order(s)")

            withContext(Dispatchers.Main) {
                if (orders.isEmpty()) {
                    extractAndConfirm(messages, customer)
                    return@withContext
                }

                ReviewQueue.offer(
                    orders.map { ReviewQueue.Pending(it, chatId, it.customer ?: customer) },
                    if (ShopSettings.language(this@OverlayService) == app.vaaniledger.data.AppLanguage.EN)
                        "$customer's chat"
                    else
                        "$customer ki chat se",
                )
                startActivity(
                    android.content.Intent(
                        this@OverlayService,
                        app.vaaniledger.capture.ReviewActivity::class.java,
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
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
            toast(captureStrings().noOrderFound)
            return
        }
        extractText(text, senderName)
    }

    /** Extract any text — chat capture and voice both land here. */
    private fun extractText(text: String, senderName: String?) {
        scope.launch {
            val db = LedgerDatabase.get(this@OverlayService)
            val known = runCatching { db.customers().names(SHOP_ID) }.getOrDefault(emptyList())

            // On-device, always. The same rules engine the server runs is
            // ported to Kotlin and held to identical answers by the parity
            // suite, so a shop counter with no signal gets the amount, the
            // mode and the items filled in -- and a shop WITH signal no
            // longer waits on a round trip to get the same result.
            val result = OfflineExtractor.extract(
                text = text,
                knownCustomers = known,
                senderName = senderName,
                category = ShopSettings.category(this@OverlayService),
            )

            withContext(Dispatchers.Main) { showConfirm(result) }
        }
    }

    private fun showConfirm(extraction: Extraction) {
        if (confirmSheet != null) return
        collapse()   // never leave the panel behind the sheet

        val view = ConfirmSheetView(
            context = this,
            extraction = extraction,
            onConfirm = { save(extraction) },
            onEdit = { dismissConfirm(); showEdit(extraction) },
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

    /**
     * "Badlo" — let the human overrule the engine.
     *
     * The window is focusable (no NOT_FOCUSABLE flag) so its EditTexts can
     * take the keyboard, and SOFT_INPUT_ADJUST_RESIZE keeps the fields
     * visible above it.
     */
    private fun showEdit(extraction: Extraction) {
        if (editSheet != null) return

        val view = EditSheetView(
            context = this,
            extraction = extraction,
            onSave = { corrected -> saveCorrected(extraction, corrected) },
            onDismiss = { dismissEdit() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(view, params)
        editSheet = view
        bubble?.visibility = View.GONE
    }

    private fun dismissEdit() {
        editSheet?.let { windowManager.removeView(it) }
        editSheet = null
        bubble?.visibility = View.VISIBLE
    }

    /**
     * Save the human's version, then feed the learning loop.
     *
     * The corrected entry is written with confidence 1.0 — a human said so,
     * and the whole confidence system exists to route attention, which this
     * entry no longer needs.
     *
     * Then the (predicted, corrected) pair goes to /corrections. Strictly
     * best-effort: the correction store improving future extractions must
     * never block the save that already happened. Losing a training example
     * is a shame; losing an entry is money.
     */
    private fun saveCorrected(predicted: Extraction, corrected: EditSheetView.Corrected) {
        dismissEdit()

        scope.launch {
            val db = LedgerDatabase.get(this@OverlayService)
            runCatching {
                db.shops().upsert(ShopEntity(id = SHOP_ID, name = SHOP_NAME))

                val customer = corrected.customer
                    ?.let { db.customers().resolve(SHOP_ID, it) }

                db.transactions().upsert(
                    TransactionEntity(
                        shopId = SHOP_ID,
                        customerId = customer?.id,
                        customerName = customer?.name,
                        amount = corrected.amount ?: 0.0,
                        mode = corrected.mode,
                        direction = predicted.direction.takeIf { it != "unknown" } ?: "sale",
                        items = corrected.items.joinToString(","),
                        source = "chat",
                        confidence = 1.0,   // human-confirmed
                    )
                )
            }.onFailure { android.util.Log.e(TAG, "saveCorrected failed", it) }

            // The learning loop: what the engine said vs what the human said.
            runCatching {
                ApiClient.api(this@OverlayService).correction(
                    app.vaaniledger.net.CorrectionRequest(
                        shopId = SHOP_ID,
                        rawText = predicted.text,
                        predicted = mapOf(
                            "amount" to (predicted.amount?.toString() ?: ""),
                            "customer" to (predicted.customer ?: ""),
                            "mode" to predicted.mode,
                            "items" to predicted.items.joinToString(","),
                        ),
                        corrected = mapOf(
                            "amount" to (corrected.amount?.toString() ?: ""),
                            "customer" to (corrected.customer ?: ""),
                            "mode" to corrected.mode,
                            "items" to corrected.items.joinToString(","),
                        ),
                    )
                )
            }.onSuccess {
                android.util.Log.i(TAG, "correction stored (${it.stored} total)")
            }.onFailure {
                android.util.Log.w(TAG, "correction upload failed: ${it.message}")
            }

            withContext(Dispatchers.Main) {
                val amount = corrected.amount?.let { "₹%,.0f".format(it) } ?: "entry"
                toast("$amount saved (badla hua)")
            }
            SyncWorker.syncNow(this@OverlayService, SHOP_ID, SHOP_NAME)
        }
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

    private fun captureStrings() =
        app.vaaniledger.data.Strings.of(ShopSettings.language(this)).capture

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
        khataSheet?.let { windowManager.removeView(it) }
        editSheet?.let { windowManager.removeView(it) }
        bubble = null
        panel = null
        confirmSheet = null
        khataSheet = null
        editSheet = null
        isRunning = false
        scope.cancel()
    }

    companion object {
        /** Read by MainActivity to label its start/stop button. */
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_EXTRACT_TEXT = "app.vaaniledger.EXTRACT_TEXT"
        const val EXTRA_TEXT = "text"

        private const val TAG = "VaaniCapture"
        /** Long enough for our own panel to be gone before we read the window. */
        /** Enough to cover a normal back-and-forth without a long stall. */
        private const val HARVEST_SCROLLS = 12
        private const val PANEL_SETTLE_MS = 350L
        /** Enough for a three-message order, not the whole scrollback. */
        private const val MAX_MESSAGES_PER_CAPTURE = 6
        // TODO(week 3): from onboarding, once a shop can be created in-app.
        // Delegated so the overlay, the review queue and the import all
        // write into the SAME khata. Three entry points with their own copy
        // of this string would split one ledger into three half-empty ones.
        private val SHOP_ID = app.vaaniledger.data.ShopSettings.ID
        private val SHOP_NAME = app.vaaniledger.data.ShopSettings.NAME
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
