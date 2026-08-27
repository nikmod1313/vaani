package app.vaaniledger

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.vaaniledger.overlay.OverlayService

/**
 * The setup screen.
 *
 * Its whole job is getting two permissions granted, because neither can be
 * requested with a normal dialog -- both send the user into a Settings screen
 * and hope they come back. Showing live status for each is the difference
 * between "it doesn't work" and "you haven't switched it on yet".
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF7, 0xF6, 0xF3))
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "VaaniLedger"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Aapka khata, aapki awaaz mein"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(28))
        })

        overlayRow = StatusRow(
            "Screen ke upar dikhana",
            "Bubble ko WhatsApp ke upar aane deta hai. Iske bina app kaam nahi karega.",
        ) { openOverlaySettings() }
        root.addView(overlayRow.view)

        accessibilityRow = StatusRow(
            "Chat padhne ki ijaazat",
            "Sirf WhatsApp. Sirf jab aap Capture dabate hain. Background mein kabhi nahi.",
        ) { openAccessibilitySettings() }
        root.addView(accessibilityRow.view)

        floaterButton = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(indigo)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { toggleFloater() }
        }
        root.addView(floaterButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) })

        root.addView(TextView(this).apply {
            text = "Bubble chalu karne ke baad WhatsApp kholiye — bubble upar dikhega."
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Both permissions are granted in Settings, so re-read them every time
        // the user comes back rather than trusting a cached value.
        refresh()
    }

    private fun refresh() {
        val canOverlay = canDrawOverlays()
        overlayRow.setGranted(canOverlay)
        accessibilityRow.setGranted(isAccessibilityEnabled())

        floaterButton.isEnabled = canOverlay
        floaterButton.alpha = if (canOverlay) 1f else 0.4f
        floaterButton.text = when {
            !canOverlay -> "Pehle permission dijiye"
            OverlayService.isRunning -> "Bubble band karein"
            else -> "Bubble chalu karein"
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

    // ----------------------------------------------------------------------

    private inner class StatusRow(
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(0xDF, 0xDC, 0xD5))
            }
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = description
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, 0)
            })
            addView(status)

            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(12)
        }.also {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(12)
            it.layoutParams = lp
        }

        fun setGranted(granted: Boolean) {
            status.text = if (granted) "✓ CHALU HAI" else "✕ BAND HAI — DABA KAR CHALU KAREIN"
            status.setTextColor(if (granted) good else warn)
        }
    }
}
