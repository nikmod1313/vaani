package app.vaaniledger.overlay

import app.vaaniledger.ui.SealFrame
import app.vaaniledger.ui.SheetBackground
import app.vaaniledger.ui.Theme
import app.vaaniledger.ui.Type
import app.vaaniledger.ui.Views.pressable
import app.vaaniledger.ui.Views.withAlpha

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
import app.vaaniledger.data.ShopSettings
import app.vaaniledger.data.Strings
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
    /**
     * Show the sale / payment-received choice. Off when correcting a capture
     * -- the engine already decided which it was -- and on for an entry
     * written by hand, where only the shopkeeper knows.
     */
    private val askDirection: Boolean = false,
    /** Heading override, for "write a new entry" rather than "change". */
    private val title: String? = null,
) : LinearLayout(context) {

    /** The human's version of the entry. This wins over the engine's. */
    data class Corrected(
        val amount: Double?,
        val customer: String?,
        val mode: String,
        val items: List<String>,
        val direction: String = "sale",
    )

    private val t = Strings.of(ShopSettings.language(context)).sheets
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
    // Declared before init, which builds the direction row and fills these.
    private var selectedDirection: String =
        extraction.direction.takeIf { it == "payment" } ?: "sale"
    private val directionChips = mutableMapOf<String, TextView>()

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(22))
        background = SheetBackground(context)
        elevation = dp(20).toFloat()

        addView(TextView(context).apply {
            text = title ?: t.editTitle
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Type.display(context)
        })
        addView(TextView(context).apply {
            text = t.editSubtitle
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Type.displayItalic(context)
            setPadding(0, dp(2), 0, dp(10))
        })

        if (askDirection) addView(directionRow())

        amountField = field(
            t.amountLabel,
            extraction.amount?.let { "%.0f".format(it) } ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
        customerField = field(t.fieldCustomer, extraction.customer ?: "", InputType.TYPE_CLASS_TEXT)
        itemsField = field(
            t.itemsLabel,
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
            setTextColor(Theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Type.bold(context)
            letterSpacing = app.vaaniledger.ui.Views.tracking(text, 0.12f)
            setPadding(0, dp(8), 0, dp(3))
        })
        val edit = EditText(context).apply {
            setText(value)
            this.inputType = inputType
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Type.body(context)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(Theme.surface)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), rule)
            }
        }
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return edit
    }

    private fun modeRow(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = t.fieldMode
            setTextColor(Theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Type.bold(context)
            letterSpacing = app.vaaniledger.ui.Views.tracking(text, 0.12f)
            setPadding(0, dp(8), 0, dp(3))
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf(t.modeCash to "cash", t.modeUpi to "upi", t.modeCredit to "udhaar")
                .forEachIndexed { i, (label, value) ->
                    if (i > 0) addView(View(context), LayoutParams(dp(8), 1))
                    val chip = TextView(context).apply {
                        text = label
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Type.medium(context)
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        pressable()
                        setOnClickListener { select(value) }
                    }
                    modeChips[value] = chip
                    addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
        })
        select(selectedMode)
    }

    private fun directionRow(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = t.fieldDirection
            setTextColor(Theme.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Type.bold(context)
            letterSpacing = app.vaaniledger.ui.Views.tracking(text, 0.12f)
            setPadding(0, dp(8), 0, dp(3))
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            listOf(t.dirSale to "sale", t.dirPayment to "payment").forEachIndexed { i, (label, value) ->
                if (i > 0) addView(View(context), LayoutParams(dp(8), 1))
                val chip = TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Type.medium(context)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    pressable()
                    setOnClickListener { selectDirection(value) }
                }
                directionChips[value] = chip
                addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        selectDirection(selectedDirection)
    }

    private fun selectDirection(direction: String) {
        selectedDirection = direction
        directionChips.forEach { (value, chip) ->
            val on = value == direction
            chip.setTextColor(if (on) Theme.onPrimary else ink)
            chip.background = GradientDrawable().apply {
                setColor(if (on) (if (value == "payment") good else Theme.primary) else Color.TRANSPARENT)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (on) Color.TRANSPARENT else rule)
            }
        }
    }

    private fun select(mode: String) {
        selectedMode = mode
        modeChips.forEach { (value, chip) ->
            val on = value == mode
            chip.setTextColor(if (on) Theme.onPrimary else ink)
            chip.background = GradientDrawable().apply {
                setColor(if (on) Theme.primary else Color.TRANSPARENT)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (on) Theme.primary else rule)
            }
        }
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(14), 0, 0)

        addView(TextView(context).apply {
            text = t.cancel
            gravity = Gravity.CENTER
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Type.medium(context)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), ink.withAlpha(0x55))
            }
            pressable()
            setOnClickListener { onDismiss() }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(View(context), LayoutParams(dp(9), 1))

        addView(TextView(context).apply {
            text = t.save
            gravity = Gravity.CENTER
            setTextColor(Theme.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Type.medium(context)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = SealFrame(context, fill = good)
            pressable()
            setOnClickListener {
                onSave(
                    Corrected(
                        amount = amountField.text.toString().trim()
                            .replace(",", "").toDoubleOrNull(),
                        customer = customerField.text.toString().trim()
                            .ifBlank { null },
                        mode = selectedMode,
                        // "do kurti" is two kurtis, not one item named "do kurti".
                        items = itemsField.text.toString()
                            .split(",").map { it.trim() }.filter { it.isNotBlank() }
                            .map { app.vaaniledger.extract.RulesEngine.normaliseLabel(it) },
                        direction = selectedDirection,
                    )
                )
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
