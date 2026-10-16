package dev.resshortcut.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale

/** Pequeno conjunto de componentes no estilo preto AMOLED (usado pelo launcher e pelo perfil). */
class Ui(private val ctx: Context) {
    val white = Color.WHITE
    val soft = Color.parseColor("#E0E0E0")
    private val line = Color.parseColor("#3A3A3A")

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun add(parent: LinearLayout, v: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(top), 0, dp(bottom)) })
    }

    fun label(s: String, size: Float = 14f, color: Int = white, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun section(s: String) = label(s.uppercase(Locale.getDefault()), 12f, soft, true).apply {
        letterSpacing = 0.1f
    }

    fun box(fill: String, radius: Int = 12) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), line)
    }

    fun button(caption: String, onClick: () -> Unit) = Button(ctx).apply {
        text = caption
        isAllCaps = false
        textSize = 15f
        setTextColor(white)
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), box("#141414"), null)
        setOnClickListener { onClick() }
    }

    fun field(hintText: String, numeric: Boolean = true) = EditText(ctx).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#808080"))
        setTextColor(white)
        textSize = 16f
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setSingleLine()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = box("#0E0E0E", 10)
    }

    fun toggle(caption: String) = Switch(ctx).apply {
        text = caption
        textSize = 15f
        setTextColor(white)
    }

    fun spinner(items: List<String>, selected: Int, onPick: (Int) -> Unit) = Spinner(ctx).apply {
        adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(white)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
        }
        setPopupBackgroundDrawable(ColorDrawable(Color.parseColor("#161616")))
        setSelection(selected.coerceIn(0, items.size - 1))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
