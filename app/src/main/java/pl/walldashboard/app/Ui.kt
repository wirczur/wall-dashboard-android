package pl.walldashboard.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny view helpers – the setup and settings screens are built in code (no layout files, no libraries). */
object Ui {
    fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** scrollable column that also fits the 480×480 panel */
    fun page(a: Activity): LinearLayout {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 16))
        }
        val sv = ScrollView(a).apply {
            setBackgroundColor(Color.parseColor("#13241D"))
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        a.setContentView(sv)
        return col
    }

    fun text(a: Activity, s: String, sp: Float = 15f, bold: Boolean = false, color: String = "#F4EBDD"): TextView = TextView(a).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(a, 4), 0, dp(a, 4))
    }

    fun button(a: Activity, label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(a).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(if (primary) "#2F8A57" else "#2A3B33"))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52)).apply { topMargin = dp(a, 8) }
    }
}
