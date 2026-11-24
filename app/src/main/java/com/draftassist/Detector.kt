package com.draftassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * Reads the MLBB draft screen. All positions are FRACTIONS of the screen, measured on a
 * 2340x1080 landscape screenshot, so they scale to other resolutions of the same layout.
 * If your phone's draft layout differs (notch, aspect ratio), tune the constants below.
 */
class Detector(private val ctx: Context, private val engine: DraftEngine) {

    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ---- layout (fractions) ----
    private val rowTop = floatArrayOf(125f, 297f, 470f, 642f, 812f).map { it / 1080f }
    private val nameY = floatArrayOf(276f, 448f, 620f, 792f, 965f).map { it / 1080f }
    private val allyX = 0.05f to 0.14f          // splash art, ally side
    private val enemyX = 0.87f to 0.96f         // splash art, enemy side
    private val banY = 45f / 1080f
    private val allyBanX = floatArrayOf(150f, 260f, 370f, 480f, 590f).map { it / 2340f }
    private val enemyBanX = floatArrayOf(1747f, 1857f, 1967f, 2077f, 2187f).map { it / 2340f }

    // ---- hero reference signatures: assets/heroes/<slug>.png, <slug>_2.png, ... ----
    private val refs: List<Pair<String, FloatArray>> by lazy { loadRefs() }

    data class Result(val state: DraftState, val userRow: Int, val inDraft: Boolean, val debug: String)

    fun process(bmp: Bitmap): Result? {
        val w = bmp.width; val h = bmp.height
        val text = Tasks.await(ocr.process(InputImage.fromBitmap(bmp, 0)))

        var inDraft = false
        val lanes = HashSet<String>()
        var lines = 0
        val avail = LinkedHashSet<String>()
        for (b in text.textBlocks) for (line in b.lines) {
            val t = line.text; lines++
            val box: Rect = line.boundingBox ?: continue
            val cx = box.centerX() / w.toFloat(); val cy = box.centerY() / h.toFloat()
            if (t.contains("Team Pick", true) || t.contains("Ban Phase", true)) inDraft = true
            if (cy in 0.10f..0.22f && cx in 0.2f..0.8f) lanes += lanesIn(t)
            if (cx in 0.24f..0.80f && cy in 0.25f..0.85f) engine.resolve(t)?.let { avail += it.name }
        }
        if (!inDraft && avail.size < 3) return null

        val autoLane = if (lanes.size == 1) lanes.first() else null
        val userRow = findUserRow(bmp)
        val allies = ArrayList<String>(); val enemies = ArrayList<String>(); val bans = HashSet<String>()
        for (i in 0 until 5) {
            if (i != userRow) matchRegion(bmp, allyX, rowTop[i]).let { if (it != null) allies += it }
            matchRegion(bmp, enemyX, rowTop[i])?.let { enemies += it }
            matchBan(bmp, allyBanX[i])?.let { bans += it }
            matchBan(bmp, enemyBanX[i])?.let { bans += it }
        }
        return Result(DraftState(autoLane, allies, enemies, bans, if (avail.size >= 3) avail else null), userRow, true,
            "text:$lines grid:${avail.size} ally:${allies.size} enemy:${enemies.size} bans:${bans.size} refs:${refs.size}")
    }

    private fun lanesIn(t: String): Set<String> {
        val s = t.lowercase(); val r = HashSet<String>()
        if ("gold" in s) r += "gold"
        if ("exp" in s) r += "exp"
        if ("mid" in s) r += "mid"
        if ("jungl" in s) r += "jungle"
        if ("roam" in s) r += "roam"
        return r
    }

    /** The user's name is drawn in gold. Count gold-ish pixels in each ally name strip. */
    private fun findUserRow(b: Bitmap): Int {
        var best = -1; var bestCount = 12
        for (i in 0 until 5) {
            val x0 = (0.07f * b.width).toInt(); val x1 = (0.17f * b.width).toInt()
            val y0 = ((nameY[i] - 0.02f) * b.height).toInt(); val y1 = ((nameY[i] + 0.02f) * b.height).toInt()
            var n = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val p = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
                if (Color.red(p) > 200 && Color.green(p) in 160..225 && Color.blue(p) < 110) n++
            }
            if (n > bestCount) { bestCount = n; best = i }
        }
        return best
    }

    // ---------- image matching ----------
    private fun matchRegion(b: Bitmap, x: Pair<Float, Float>, top: Float): String? {
        val r = Rect((x.first * b.width).toInt(), ((top + 0.015f) * b.height).toInt(),
            (x.second * b.width).toInt(), ((top + 0.13f) * b.height).toInt())
        return matchRect(b, r)
    }

    private fun matchBan(b: Bitmap, cx: Float): String? {
        val half = (0.032f * b.height).toInt()
        val c = (cx * b.width).toInt(); val cy = (banY * b.height).toInt()
        return matchRect(b, Rect(c - half, cy - half, c + half, cy + half))
    }

    private fun matchRect(b: Bitmap, r: Rect): String? {
        r.intersect(0, 0, b.width, b.height)
        if (r.width() < 8 || r.height() < 8) return null
        val sig = signature(Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()))
        if (sig.second < 12f) return null                      // flat/empty slot (low contrast)
        var best = Float.MAX_VALUE; var bestName: String? = null
        for ((name, ref) in refs) { val d = dist(sig.first, ref); if (d < best) { best = d; bestName = name } }
        return if (best < THRESHOLD) bestName else null
    }

    /** 16x16 RGB, brightness-normalised. Returns (signature, contrast). */
    private fun signature(src: Bitmap): Pair<FloatArray, Float> {
        val s = Bitmap.createScaledBitmap(src, 16, 16, true)
        val v = FloatArray(16 * 16 * 3); var k = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = s.getPixel(x, y)
            v[k++] = Color.red(p).toFloat(); v[k++] = Color.green(p).toFloat(); v[k++] = Color.blue(p).toFloat()
        }
        val mean = v.average().toFloat()
        var dev = 0f
        for (i in v.indices) { v[i] -= mean; dev += abs(v[i]) }
        return v to dev / v.size
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var d = 0f; for (i in a.indices) d += abs(a[i] - b[i]); return d / a.size
    }

    private fun loadRefs(): List<Pair<String, FloatArray>> {
        val slugToName = engine.heroes.associate { it.slug to it.name }
        val out = ArrayList<Pair<String, FloatArray>>()
        val files = ctx.assets.list("heroes") ?: return out
        for (f in files) {
            val slug = f.substringBeforeLast('.').substringBefore('_')
            val name = slugToName[slug] ?: continue
            ctx.assets.open("heroes/$f").use { ins ->
                android.graphics.BitmapFactory.decodeStream(ins)?.let { out += name to signature(it).first }
            }
        }
        return out
    }

    companion object { const val THRESHOLD = 22f }   // tune with real screenshots
}
