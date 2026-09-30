package app.vaaniledger.overlay

import app.vaaniledger.ui.Theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.vaaniledger.net.Extraction

/**
 * "Badlo" — the edit sheet.
 *
 * This screen matters twice over. Once for the obvious reason: the engine is
 * good, not perfect, and a shopkeeper must be able to fix a wrong field
 * before it becomes a wrong number in someone's khata.
 *
 * And once for the less obvious one: every correction made here is the
 * training signal for the learning loop. What he changes, paired with what
 * the engine predicted, is stored and replayed to the model as a few-shot
 * example for THIS shop — which is what "the system learns from you"
 * actually means. A dead Badlo button meant no corrections, which meant no
 * learning, which meant the claim was empty.
 */
@SuppressLint("ViewConstructor")
class EditSheetView(
    context: Context,
    private val extraction: Extraction,
    private val onSave: (Corrected) -> Unit,
    private val onDismiss: () -> Unit,
) : LinearLayout(context) {

    /** The human's version of the entry. This wins over the engine's. */
    data class Corrected(
        val amount: Double?,
        val customer: String?,
        val mode: String,
        val items: List<String>,
    )

    private val ink = Theme.ink
    private val muted = Theme.muted
    private val rule = Theme.border
    private val good = Theme.good

    private lateinit var amountField: EditText
    private lateinit var customerField: EditText
    private lateinit var itemsField: EditText
    private var selectedMode: String =
        extraction.mode.takeIf { it != "unknown" } ?: "udhaar"
    private val modeChips = mutableMapOf<String, TextView>()

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(),
                0f, 0f, 0f, 0f,
            )
        }
        elevation = dp(20).toFloat()

        addView(TextView(context).apply {
            text = "Badlo"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = "Jo galat hai use theek kijiye — aapki baat final hai"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(2), 0, dp(10))
        })

        amountField = field(
            "RAKAM (₹)",
            extraction.amount?.let { "%.0f".format(it) } ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
        customerField = field("GRAHAK", extraction.customer ?: "", InputType.TYPE_CLASS_TEXT)
        itemsField = field(
            "SAMAN (comma se alag)",
            extraction.items.joinToString(", "),
            InputType.TYPE_CLASS_TEXT,
        )
        addView(modeRow())
        addView(footer())
    }

    // ----------------------------------------------------------------------

    private fun field(label: String, value: String, inputType: Int): EditText {
        addView(TextView(context).apply {
            text = label
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            letterSpacing = 0.1f
            setPadding(0, dp(8), 0, dp(3))
        })
        val edit = EditText(context).apply {
            setText(value)
            this.inputType = inputType
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Theme.subtle)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), rule)
            }
        }
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return edit
    }

    private fun modeRow(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = "TYPE"
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            letterSpacing = 0.1f
            setPadding(0, dp(8), 0, dp(3))
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf("nakad" to "cash", "UPI" to "upi", "udhaar" to "udhaar")
                .forEachIndexed { i, (label, value) ->
                    if (i > 0) addView(View(context), LayoutParams(dp(8), 1))
                    val chip = TextView(context).apply {
                        text = label
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(8), dp(9), dp(8), dp(9))
                        isClickable = true
                        setOnClickListener { select(value) }
                    }
                    modeChips[value] = chip
                    addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
        })
        select(selectedMode)
    }

    private fun select(mode: String) {
        selectedMode = mode
        modeChips.forEach { (value, chip) ->
            val on = value == mode
            chip.setTextColor(if (on) Color.WHITE else ink)
            chip.background = GradientDrawable().apply {
                setColor(if (on) BubbleView.INDIGO else Theme.subtle)
                cornerRadius = dp(9).toFloat()
            }
        }
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(14), 0, 0)

        addView(TextView(context).apply {
            text = "Cancel"
            gravity = Gravity.CENTER
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = GradientDrawable().apply {
                setColor(Theme.subtle); cornerRadius = dp(11).toFloat()
            }
            isClickable = true
            setOnClickListener { onDismiss() }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(View(context), LayoutParams(dp(9), 1))

        addView(TextView(context).apply {
            text = "Save karo  ✓"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = GradientDrawable().apply {
                setColor(good); cornerRadius = dp(11).toFloat()
            }
            isClickable = true
            setOnClickListener {
                onSave(
                    Corrected(
                        amount = amountField.text.toString().trim()
                            .replace(",", "").toDoubleOrNull(),
                        customer = customerField.text.toString().trim()
                            .ifBlank { null },
                        mode = selectedMode,
                        items = itemsField.text.toString()
                            .split(",").map { it.trim() }.filter { it.isNotBlank() },
                    )
                )
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
