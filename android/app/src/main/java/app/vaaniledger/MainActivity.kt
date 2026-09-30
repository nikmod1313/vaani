package app.vaaniledger

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.vaaniledger.capture.ImportExportActivity
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
import app.vaaniledger.overlay.OverlayService
import app.vaaniledger.reminder.ReminderLinks

/**
 * The home screen.
 *
 * Redirects to [OnboardingActivity] until category and language are both
 * chosen -- exactly once, ever -- then shows the two permissions (each can
 * only be granted from a Settings screen, so live status is the difference
 * between "broken" and "not switched on yet") and the two ways to read an
 * order. What the shop sells no longer lives here as a card that looked
 * identical to an unfinished permission and asked again on every visit; it
 * is one line under the gear icon now, changed on purpose rather than by
 * accident.
 */
class MainActivity : Activity() {

    private lateinit var overlayRow: StatusRow
    private lateinit var accessibilityRow: StatusRow
    private lateinit var floaterButton: TextView

    private val ink = Color.rgb(0x17, 0x19, 0x1E)
    private val muted = Color.rgb(0x7A, 0x7E, 0x88)
    private val indigo = Color.rgb(0x25, 0x3A, 0x6E)
    private val good = Color.rgb(0x2C, 0x61, 0x49)
    private val warn = Color.rgb(0x8A, 0x5D, 0x0B)
    private val line = Color.rgb(0xDF, 0xDC, 0xD5)

    private val strings get() = Strings.of(ShopSettings.language(this)).main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ShopSettings.hasCompletedOnboarding(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        build()
    }

    override fun onResume() {
        super.onResume()
        // Onboarding may have just run, permissions may have just been
        // granted in Settings, the language may have just changed --
        // rebuilding on every return is simpler and cheaper than tracking
        // which of those happened.
        build()
    }

    // ----------------------------------------------------------------------

    private fun build() {
        val t = strings

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF7, 0xF6, 0xF3))
            setPadding(dp(24), dp(36), dp(24), dp(32))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = t.appTitle
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = t.tagline
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = "⚙️"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            })
        })

        root.addView(View(this), LinearLayout.LayoutParams(0, dp(22)))

        overlayRow = StatusRow(
            "📱", t.overlayTitle, t.overlayDesc,
        ) { openOverlaySettings() }
        root.addView(overlayRow.view)

        accessibilityRow = StatusRow(
            "💬", t.accessibilityTitle, t.accessibilityDesc,
        ) { openAccessibilitySettings() }
        root.addView(accessibilityRow.view)

        floaterButton = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(indigo)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { toggleFloater() }
        }
        root.addView(floaterButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = t.helperText
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(26))
        })

        // ------------------------------------------------------------------
        // The two ingestion lanes, deliberately on separate buttons.
        //
        // They differ in more than plumbing and the shopkeeper should be able
        // to pick. Screening reads the chat that is open right now and costs
        // nothing; the export hands over the entire history but has to be
        // started from inside WhatsApp. Hiding either behind the other would
        // mean guessing which one someone wanted.
        // ------------------------------------------------------------------

        root.addView(TextView(this).apply {
            text = t.waysHeading
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(actionCard("💬", t.openWhatsAppTitle, t.openWhatsAppDesc) { openWhatsApp() })
        root.addView(actionCard("📄", t.importTitle, t.importDesc) { pickExportFile() })

        // Reading the business, as opposed to recording it. Separated under
        // its own heading because these two are the shopkeeper's OWN pages --
        // nothing here captures anything, and both work with no signal.
        root.addView(TextView(this).apply {
            text = t.businessHeading
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(10))
        })
        root.addView(actionCard("📊", t.analyticsTitle, t.analyticsDesc) {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        })
        root.addView(actionCard("📒", t.ledgerTitle, t.ledgerDesc) {
            startActivity(Intent(this, LedgerActivity::class.java))
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun refresh() {
        val t = strings
        val canOverlay = canDrawOverlays()
        overlayRow.setGranted(canOverlay, t)
        accessibilityRow.setGranted(isAccessibilityEnabled(), t)

        floaterButton.isEnabled = canOverlay
        floaterButton.alpha = if (canOverlay) 1f else 0.4f
        floaterButton.text = when {
            !canOverlay -> "🔒  ${t.needPermissionFirst}"
            OverlayService.isRunning -> "⏹  ${t.bubbleStop}"
            else -> "▶️  ${t.bubbleStart}"
        }
    }

    private fun toggleFloater() {
        if (!canDrawOverlays()) {
            openOverlaySettings()
            return
        }
        if (OverlayService.isRunning) OverlayService.stop(this) else OverlayService.start(this)
        // The service flips its flag in onCreate/onDestroy; give it a beat.
        floaterButton.postDelayed({ refresh() }, 250)
    }

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

    private fun actionCard(icon: String, title: String, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), line)
            }
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, dp(14), 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(indigo)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_EXPORT = 4201
    }

    // ----------------------------------------------------------------------

    private inner class StatusRow(
        icon: String,
        title: String,
        description: String,
        onClick: () -> Unit,
    ) {
        private val status = TextView(this@MainActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        }

        val view: View = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), line)
            }
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setPadding(0, 0, dp(12), 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = description
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(status)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }.also {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(12)
            it.layoutParams = lp
        }

        fun setGranted(granted: Boolean, t: Strings.Main) {
            status.text = if (granted) "✓ ${t.statusOn}" else "✕ ${t.statusOffPrefix}"
            status.setTextColor(if (granted) good else warn)
        }
    }
}
