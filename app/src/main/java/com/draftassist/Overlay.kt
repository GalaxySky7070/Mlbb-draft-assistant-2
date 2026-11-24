package com.draftassist

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Semi-transparent, draggable panel. Tap the title to collapse/expand. */
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = LinearLayout(ctx)
    private val title = TextView(ctx)
    private val body = LinearLayout(ctx)
    private var collapsed = false
    private val lp = WindowManager.LayoutParams(
        dp(230), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(300); y = dp(70) }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(8), dp(10), dp(8))
        root.background = GradientDrawable().apply {
            setColor(Color.argb(150, 12, 18, 40)); cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.argb(120, 120, 170, 255))
        }
        title.setTextColor(Color.WHITE); title.textSize = 13f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(title); body.orientation = LinearLayout.VERTICAL; root.addView(body)

        var dx = 0f; var dy = 0f; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - dx).toInt(); lp.y = (e.rawY - dy).toInt(); moved = true
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    collapsed = !collapsed; body.visibility = if (collapsed) View.GONE else View.VISIBLE
                }
            }
            true
        }
        wm.addView(root, lp)
    }

    fun show(status: String, recs: List<Rec>, enemies: List<String>, allies: List<String>) {
        title.text = status
        body.removeAllViews()
        if (enemies.isNotEmpty() || allies.isNotEmpty())
            body.addView(line("Allies: ${allies.joinToString().ifEmpty { "-" }}\nEnemy: ${enemies.joinToString().ifEmpty { "-" }}",
                Color.argb(200, 170, 190, 230), 10f))
        recs.take(4).forEachIndexed { i, r ->
            body.addView(line("${i + 1}. ${r.hero.name}", if (i == 0) Color.rgb(255, 214, 90) else Color.WHITE, if (i == 0) 15f else 13f, true))
            if (r.reasons.isNotEmpty()) body.addView(line(r.reasons.joinToString(" · "), Color.argb(210, 160, 220, 170), 10f))
        }
    }

    private fun line(t: String, color: Int, size: Float, bold: Boolean = false) = TextView(ctx).apply {
        text = t; setTextColor(color); textSize = size; setPadding(0, dp(2), 0, 0)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    fun remove() { try { wm.removeView(root) } catch (_: Exception) {} }
}
