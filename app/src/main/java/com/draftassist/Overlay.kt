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

/** Semi-transparent, draggable panel. Tap the title to collapse. Tap a lane to pick your lane. */
class Overlay(private val ctx: Context, private val onLane: (String) -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = LinearLayout(ctx)
    private val title = TextView(ctx)
    private val body = LinearLayout(ctx)
    private val laneRow = LinearLayout(ctx)
    private val laneViews = LinkedHashMap<String, TextView>()
    private var collapsed = false
    private val lp = WindowManager.LayoutParams(
        dp(240), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(40) }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(8), dp(10), dp(8))
        root.background = GradientDrawable().apply {
            setColor(Color.argb(170, 12, 18, 40)); cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.argb(140, 120, 170, 255))
        }
        title.setTextColor(Color.WHITE); title.textSize = 12f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(title)

        laneRow.orientation = LinearLayout.HORIZONTAL
        for ((k, label) in listOf("gold" to "Gold", "exp" to "Exp", "mid" to "Mid", "jungle" to "Jng", "roam" to "Roam")) {
            val tv = TextView(ctx).apply {
                text = label; textSize = 11f; setTextColor(Color.WHITE)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { onLane(k) }
            }
            laneViews[k] = tv; laneRow.addView(tv)
        }
        root.addView(laneRow)
        body.orientation = LinearLayout.VERTICAL; root.addView(body)

        var dx = 0f; var dy = 0f; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - dx).toInt(); lp.y = (e.rawY - dy).toInt(); moved = true
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    collapsed = !collapsed
                    body.visibility = if (collapsed) View.GONE else View.VISIBLE
                    laneRow.visibility = body.visibility
                }
            }
            true
        }
        wm.addView(root, lp)
    }

    fun show(status: String, lane: String?, recs: List<Rec>, enemies: List<String>, allies: List<String>, debug: String = "") {
        title.text = status
        for ((k, v) in laneViews) v.setTextColor(if (k == lane) Color.rgb(255, 214, 90) else Color.argb(200, 200, 210, 235))
        body.removeAllViews()
        if (enemies.isNotEmpty() || allies.isNotEmpty())
            body.addView(line("Allies: ${allies.joinToString().ifEmpty { "-" }}\nEnemy: ${enemies.joinToString().ifEmpty { "-" }}",
                Color.argb(200, 170, 190, 230), 10f))
        recs.take(4).forEachIndexed { i, r ->
            body.addView(line("${i + 1}. ${r.hero.name}", if (i == 0) Color.rgb(255, 214, 90) else Color.WHITE, if (i == 0) 15f else 13f, true))
            if (r.reasons.isNotEmpty()) body.addView(line(r.reasons.joinToString(" · "), Color.argb(210, 160, 220, 170), 10f))
        }
        if (debug.isNotEmpty()) body.addView(line(debug, Color.argb(150, 190, 190, 190), 9f))
    }

    private fun line(t: String, color: Int, size: Float, bold: Boolean = false) = TextView(ctx).apply {
        text = t; setTextColor(color); textSize = size; setPadding(0, dp(2), 0, 0)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    fun remove() { try { wm.removeView(root) } catch (_: Exception) {} }
}
