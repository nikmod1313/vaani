package app.vaaniledger

import app.vaaniledger.ui.ArchWindow
import app.vaaniledger.ui.ArtView
import app.vaaniledger.ui.BottomNav
import app.vaaniledger.ui.LineIcon
import app.vaaniledger.ui.ManuscriptFrame
import app.vaaniledger.ui.Motion
import app.vaaniledger.ui.Nav
import app.vaaniledger.ui.Scaffold.page
import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.body
import app.vaaniledger.ui.Views.chevron
import app.vaaniledger.ui.Views.dp
import app.vaaniledger.ui.Views.eyebrow
import app.vaaniledger.ui.Views.matchWrap
import app.vaaniledger.ui.Views.medallion
import app.vaaniledger.ui.Views.pill
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.sectionHeading
import app.vaaniledger.ui.Views.text
import app.vaaniledger.ui.Views.title
import app.vaaniledger.ui.Views.weight
import app.vaaniledger.ui.Views.withAlpha

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.vaaniledger.capture.ImportExportActivity
import app.vaaniledger.data.DayTotals
import app.vaaniledger.data.LedgerDatabase
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.overlay.OverlayService
import app.vaaniledger.reminder.ReminderLinks
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen.
 *
 * Redirects to [OnboardingActivity] until category and language are both
 * chosen -- exactly once, ever -- then shows, top to bottom, the things a
 * shopkeeper opens it for:
 *
 *  - today's tally, from Room, so the first thing seen is the day's money;
 *  - the two permissions, but ONLY while one is missing. Each can only be
 *    granted from a Settings screen, so live status is the difference
 *    between "broken" and "not switched on yet" -- and once both are on, a
 *    checklist that is already complete is just noise on every visit;
 *  - the bubble, as a switch, because it is on or off and nothing else;
 *  - the two ways to read an order.
 *
 * What the shop sells is not here; it is one line in Settings, changed on
 * purpose rather than by accident.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val strings get() = Strings.of(ShopSettings.language(this)).main
    private val home get() = Strings.of(ShopSettings.language(this)).home
    private val panel get() = Strings.of(ShopSettings.language(this)).panel

    private var firstBuild = true
    private var today: DayTotals? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ShopSettings.hasCompletedOnboarding(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        // Built in onResume, which always follows.
    }

    override fun onResume() {
        super.onResume()
        // Onboarding may have just run, permissions may have just been
        // granted in Settings, the language may have just changed, an entry
        // may have just been saved from the bubble -- rebuilding on every
        // return is simpler and cheaper than tracking which of those happened.
        if (!ShopSettings.hasCompletedOnboarding(this)) return
        build()
        loadToday()
        // Opening the app brings the dashboard up to date -- and refills a
        // hosted server that forgot everything while it slept.
        app.vaaniledger.sync.SyncWorker.syncNow(this, ShopSettings.ID, ShopSettings.name(this))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------

    private lateinit var saleView: TextView
    private lateinit var cashView: TextView
    private lateinit var udhaarView: TextView
    private lateinit var entriesView: TextView

    /** The page's scroll view, so a rebuild can keep the reader where they were. */
    private fun scroller(): android.widget.ScrollView? {
        fun find(v: View): android.widget.ScrollView? = when (v) {
            is android.widget.ScrollView -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(window.decorView)
    }

    private fun build() {
        val t = strings
        val h = home
        // Toggling the bubble rebuilds the page; it should not throw the
        // shopkeeper back to the top of it.
        val keepScroll = scroller()?.scrollY ?: 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        // ---- masthead -----------------------------------------------------
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("वाणी", 22f, Theme.primary, Type.display(this@MainActivity)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(eyebrow(dateLine(), Theme.muted))
        })
        root.addView(title(greeting(), 34f).apply { setPadding(0, dp(14), 0, 0) })
        root.addView(text(t.tagline, 15f, Theme.muted, Type.displayItalic(this)).apply {
            setPadding(0, dp(2), 0, dp(20))
        })

        // ---- today --------------------------------------------------------
        root.addView(todayPanel())

        // ---- setup, only while something is missing -----------------------
        val overlayOn = canDrawOverlays()
        val accessOn = isAccessibilityEnabled()
        if (!overlayOn || !accessOn) {
            root.addView(setupCard(overlayOn, accessOn))
        }

        // ---- the bubble ---------------------------------------------------
        root.addView(bubbleCard(overlayOn))

        // ------------------------------------------------------------------
        // The two ingestion lanes, deliberately on separate cards.
        //
        // They differ in more than plumbing and the shopkeeper should be able
        // to pick. Screening reads the chat that is open right now and costs
        // nothing; the export hands over the entire history but has to be
        // started from inside WhatsApp. Hiding either behind the other would
        // mean guessing which one someone wanted.
        // ------------------------------------------------------------------
        root.addView(sectionHeading(t.waysHeading))
        root.addView(laneCard(
            LineIcon.Glyph.CHAT, t.openWhatsAppTitle, t.openWhatsAppDesc,
            art = R.drawable.art_paisley_elephants, mirror = true,
        ) { openWhatsApp() })
        root.addView(laneCard(
            LineIcon.Glyph.FILE, t.importTitle, t.importDesc,
            art = R.drawable.art_cushions, mirror = false,
        ) { pickExportFile() })

        setContentView(
            page(
                current = BottomNav.Destination.HOME,
                items = Nav.items(this),
                onNavigate = { Nav.go(this, it) },
                content = root,
            )
        )
        if (firstBuild) {
            Motion.stagger(root, startDelay = 40)
            firstBuild = false
        } else if (keepScroll > 0) {
            scroller()?.let { sv -> sv.post { sv.scrollTo(0, keepScroll) } }
        }
        today?.let { showToday(it, animate = false) }
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> home.greetingMorning
            hour < 17 -> home.greetingAfternoon
            else -> home.greetingEvening
        }
    }

    private fun dateLine(): String {
        // In the interface language: "मंगल, 27 अक्तू॰" or "Tue, 27 Oct".
        return SimpleDateFormat("EEE, d MMM", ShopSettings.language(this).locale).format(Date())
    }

    // ---- today ------------------------------------------------------------

    /**
     * Today's figures over the palace, in an indigo panel. Tapping it opens
     * the report, which is where "and how was the month?" is answered.
     */
    private fun todayPanel(): View {
        val p = panel
        val h = home
        val window = ArchWindow(this, R.drawable.art_palace, arched = false, focusY = 0.92f, shade = 0.9f)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(eyebrow(h.todayHeading, Theme.gold), weight())
            addView(text(h.seeReport, 13f, Theme.goldPale, Type.medium(this@MainActivity)))
            addView(chevron(Theme.goldPale))
        })
        saleView = text("₹0", 44f, Theme.onPrimary, Type.display(this)).apply {
            setPadding(0, dp(10), 0, 0)
        }
        inner.addView(saleView)
        entriesView = text(p.noEntriesToday, 13f, Theme.onPrimaryMuted)
        inner.addView(entriesView)

        cashView = text("₹0", 19f, CASH_ON_INDIGO, Type.display(this))
        udhaarView = text("₹0", 19f, Theme.goldPale, Type.display(this))
        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
            addView(miniStat(p.statLabelCash, cashView), weight())
            addView(View(this@MainActivity).apply {
                setBackgroundColor(Theme.gold.withAlpha(0x55))
            }, LinearLayout.LayoutParams(dp(1), -1).apply { marginEnd = dp(16) })
            addView(miniStat(p.statLabelCredit, udhaarView), weight())
        })

        window.addView(inner)
        window.pressable()
        window.setOnClickListener { Nav.go(this, BottomNav.Destination.REPORT) }
        window.layoutParams = matchWrap(dp(14))
        return window
    }

    private fun miniStat(label: String, value: TextView): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 10.5f, Theme.onPrimaryMuted, Type.bold(this@MainActivity)).apply { letterSpacing = app.vaaniledger.ui.Views.tracking(text, 0.14f) })
        addView(value)
    }

    private fun loadToday() {
        scope.launch {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            val end = start + TimeUnit.DAYS.toMillis(1) - 1
            val totals = runCatching {
                LedgerDatabase.get(this@MainActivity).transactions()
                    .dayTotalsOnce(ShopSettings.ID, start, end)
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                val first = today == null
                today = totals
                showToday(totals, animate = first)
            }
        }
    }

    private fun showToday(d: DayTotals, animate: Boolean) {
        val money = { v: Double -> "₹%,.0f".format(v) }
        if (animate) Motion.countUp(saleView, d.sale, money) else saleView.text = money(d.sale)
        cashView.text = money(d.cashIn)
        udhaarView.text = money(d.udhaar)
        entriesView.text = if (d.entries == 0) panel.noEntriesToday else home.todayEntries(d.entries)
    }

    // ---- setup ------------------------------------------------------------

    private fun setupCard(overlayOn: Boolean, accessOn: Boolean): View {
        val t = strings
        val h = home
        val done = listOf(overlayOn, accessOn).count { it }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ManuscriptFrame(this@MainActivity)
            setPadding(dp(18), dp(18), dp(18), dp(10))
            layoutParams = matchWrap(dp(14))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(eyebrow(h.setupHeading), weight())
                addView(text(h.setupProgress(done, 2), 12.5f, Theme.muted, Type.medium(this@MainActivity)))
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(6)))
            addView(stepRow(1, overlayOn, t.overlayTitle, t.overlayDesc) { openOverlaySettings() })
            addView(View(this@MainActivity).apply { setBackgroundColor(Theme.border) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(54) })
            addView(stepRow(2, accessOn, t.accessibilityTitle, t.accessibilityDesc) { openAccessibilitySettings() })
        }
    }

    private fun stepRow(n: Int, on: Boolean, title: String, desc: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            val t = strings
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            pressable()
            setOnClickListener { onClick() }

            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (on) setColor(Theme.goodSoft) else {
                        setColor(Color.TRANSPARENT); setStroke(dp(1), Theme.accent)
                    }
                }
                if (on) addView(View(this@MainActivity).apply {
                    background = LineIcon(this@MainActivity, LineIcon.Glyph.CHECK, Theme.good, sizeDp = 18)
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                else addView(text("$n", 17f, Theme.accent, Type.display(this@MainActivity)).apply {
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(16) })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, Theme.ink, Type.medium(this@MainActivity)))
                addView(body(desc, 13f).apply { setPadding(0, dp(2), 0, dp(8)) })
                addView(
                    if (on) pill(t.statusOn, Theme.good, Theme.goodSoft)
                    else pill(t.statusOffPrefix, Theme.warn, Theme.warnSoft),
                    LinearLayout.LayoutParams(-2, -2),
                )
            }, weight())
            if (!on) addView(chevron(), LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8)
            })
        }

    // ---- the bubble -------------------------------------------------------

    private fun bubbleCard(canOverlay: Boolean): View {
        val t = strings
        val h = home
        val running = OverlayService.isRunning
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ManuscriptFrame(this@MainActivity)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = matchWrap(dp(4))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                pressable()
                setOnClickListener { toggleFloater() }

                val medal = medallion(
                    if (canOverlay) LineIcon.Glyph.BUBBLE else LineIcon.Glyph.LOCK,
                    tint = if (running) Theme.gold else Theme.primary,
                    fill = if (running) Theme.primary else Theme.subtle,
                    size = 48,
                )
                addView(medal)
                if (running) breathe(medal)

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, dp(10), 0)
                    addView(text(h.bubbleHeading, 18f, Theme.ink, Type.display(this@MainActivity)))
                    addView(text(
                        when {
                            !canOverlay -> t.needPermissionFirst
                            running -> h.bubbleOnCaption
                            else -> h.bubbleOffCaption
                        },
                        13f, if (running) Theme.good else Theme.muted,
                    ))
                }, weight())

                addView(Toggle(running, enabled = canOverlay), LinearLayout.LayoutParams(dp(52), dp(30)))
            })
            addView(body(t.helperText, 12.5f).apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    /** A slow pulse on the medallion while the bubble is live: it is out there, working. */
    private fun breathe(view: View) {
        ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.72f, 1f).apply {
            duration = 2400
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    /** A switch, drawn: a track and a knob. Indigo with a gold knob when on. */
    private inner class Toggle(on: Boolean, enabled: Boolean) : FrameLayout(this@MainActivity) {
        init {
            alpha = if (enabled) 1f else 0.45f
            background = GradientDrawable().apply {
                setColor(if (on) Theme.primary else Theme.subtle)
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), if (on) Theme.primary else Theme.border)
            }
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) Theme.gold else Theme.surface)
                    setStroke(dp(1), if (on) Theme.goldPale else Theme.border)
                }
            }, LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
    }

    private fun toggleFloater() {
        if (!canDrawOverlays()) {
            openOverlaySettings()
            return
        }
        if (OverlayService.isRunning) OverlayService.stop(this) else OverlayService.start(this)
        // The service flips its flag in onCreate/onDestroy; give it a beat.
        window.decorView.postDelayed({ build() }, 250)
    }

    // ---- the two lanes ----------------------------------------------------

    /**
     * A lane: icon, serif title, description, and a piece of the art set
     * into the card's right edge -- the paisley elephants for the live chat,
     * the stacked cushions (a whole history, piled up) for the export.
     */
    private fun laneCard(
        icon: LineIcon.Glyph,
        title: String,
        subtitle: String,
        art: Int,
        mirror: Boolean,
        onClick: () -> Unit,
    ): View = FrameLayout(this).apply {
        background = ManuscriptFrame(this@MainActivity)
        pressable()
        setOnClickListener { onClick() }
        layoutParams = matchWrap(dp(12))

        // The art, mirrored so its cut edge faces into the card.
        addView(ArtView(this@MainActivity, art, alignBottom = true).apply {
            if (mirror) scaleX = -1f
            alpha = 0.95f
        }, FrameLayout.LayoutParams(dp(if (mirror) 120 else 112), -1, Gravity.END).apply {
            topMargin = dp(if (mirror) 1 else 14)
            bottomMargin = dp(if (mirror) 3 else 10)
            marginEnd = dp(if (mirror) 1 else 8)
        })

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(128), dp(18))
            addView(View(this@MainActivity).apply {
                background = LineIcon(this@MainActivity, icon, Theme.accent, sizeDp = 24)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text(title, 20f, Theme.ink, Type.display(this@MainActivity)).apply {
                setPadding(0, dp(10), 0, dp(2))
            })
            addView(body(subtitle, 13f))
        }, FrameLayout.LayoutParams(-1, -2))
    }

    // ---- actions (unchanged behaviour) ------------------------------------

    /**
     * Open WhatsApp, with the bubble already running.
     *
     * Starting the floater first is the whole point: screening happens from
     * the bubble once a chat is on screen, so opening WhatsApp without it
     * would drop the shopkeeper into a chat with no way to read it.
     */
    private fun openWhatsApp() {
        val t = strings
        if (!canDrawOverlays()) {
            toast(t.toastNoOverlayFirst)
            openOverlaySettings()
            return
        }
        if (!isAccessibilityEnabled()) {
            // The bubble would open, but Capture could never read anything --
            // the exact failure the screenshots showed. Sending the
            // shopkeeper straight to the missing permission beats a bubble
            // that quietly does nothing when tapped.
            toast(t.accessibilityDesc)
            openAccessibilitySettings()
            return
        }
        if (!OverlayService.isRunning) OverlayService.start(this)

        val launched = listOf(
            ReminderLinks.WHATSAPP_PACKAGE,
            ReminderLinks.WHATSAPP_BUSINESS_PACKAGE,
        ).any { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } ?: false
        }

        toast(if (!launched) t.toastWhatsAppNotFound else t.toastOpenChatThenBubble)
    }

    /**
     * Pick an exported chat file.
     *
     * ACTION_OPEN_DOCUMENT rather than a permission-hungry file listing: the
     * picker grants access to the one file the user chose and nothing else,
     * so this needs no storage permission at all.
     *
     * Both MIME types are offered because "Export chat -> Without media"
     * produces a .txt and "With media" produces a .zip, and shopkeepers use
     * whichever WhatsApp last left selected.
     */
    private fun pickExportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/zip"))
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (_: ActivityNotFoundException) {
            toast(strings.toastNoFilePicker)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        // Hand the Uri on with read permission attached; ImportExportActivity
        // runs in the same process but a bare Uri would still be unreadable
        // there without this flag.
        startActivity(
            Intent(this, ImportExportActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, contentResolver.getType(uri) ?: "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // ----------------------------------------------------------------------

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains("$packageName/.capture.LedgerAccessibilityService") ||
            enabled.contains("$packageName/app.vaaniledger.capture.LedgerAccessibilityService")
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private companion object {
        const val REQUEST_EXPORT = 4201

        /** Money in, legible on indigo: the semantic green, lifted. */
        val CASH_ON_INDIGO = Color.rgb(0xB9, 0xD9, 0xAE)
    }
}
