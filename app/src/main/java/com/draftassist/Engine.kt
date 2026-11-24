package com.draftassist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class Edge(val hero: String, val adv: Double)

data class Hero(
    val name: String, val slug: String, val aliases: List<String>,
    val roles: List<String>, val lanes: List<String>,
    val counteredBy: List<Edge>,      // heroes that counter this hero
    val strongAgainst: List<Edge>,    // heroes this hero beats
    val synergies: List<String>,      // empty in the current data file
    val tier: String?, val winRate: Double?
)

/** lane keys: gold, exp, mid, jungle, roam */
data class DraftState(
    val lane: String?,
    val allies: List<String>,
    val enemies: List<String>,
    val banned: Set<String>,
    val available: Set<String>?      // heroes visible in the pick grid, null = unknown
)

data class Rec(val hero: Hero, val score: Double, val reasons: List<String>)

object HeroData {
    fun load(ctx: Context): List<Hero> {
        val txt = ctx.assets.open("heroes_complete.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(txt).getJSONArray("heroes")
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun edges(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map {
        val o = a.getJSONObject(it); Edge(o.getString("hero"), o.optDouble("advantage_pct", 0.0))
    }
    private fun syn(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull {
        val v = a.get(it)
        if (v is JSONObject) v.optString("hero").ifEmpty { null } else v.toString()
    }

    private fun parse(o: JSONObject): Hero {
        val meta = o.optJSONObject("meta")
        return Hero(
            name = o.getString("name"), slug = o.optString("slug"),
            aliases = strs(o.optJSONArray("aliases")),
            roles = strs(o.optJSONArray("roles")), lanes = strs(o.optJSONArray("lanes")),
            counteredBy = edges(o.optJSONArray("counters")),
            strongAgainst = edges(o.optJSONArray("strong_against")),
            synergies = syn(o.optJSONArray("synergies")),
            tier = meta?.optString("tier")?.ifEmpty { null },
            winRate = if (meta != null && meta.has("win_rate_pct")) meta.optDouble("win_rate_pct") else null
        )
    }
}

class DraftEngine(val heroes: List<Hero>) {
    private val byName = heroes.associateBy { it.name }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Match OCR text (may be truncated with "...") to a hero. */
    fun resolve(raw: String): Hero? {
        val t = raw.trim().removeSuffix("...").removeSuffix("…").trim()
        if (t.length < 2) return null
        val n = norm(t)
        heroes.firstOrNull { norm(it.name) == n || it.aliases.any { a -> norm(a) == n } }?.let { return it }
        if (raw.contains("...") || raw.contains("…"))
            heroes.firstOrNull { norm(it.name).startsWith(n) }?.let { return it }
        return null
    }

    fun recommend(s: DraftState, top: Int = 5): List<Rec> {
        val taken = (s.allies + s.enemies).toSet() + s.banned
        val remaining = (5 - s.enemies.size).coerceAtLeast(0)
        val allyHeroes = s.allies.mapNotNull { byName[it] }
        val allyRoles = allyHeroes.flatMap { it.roles }

        val out = ArrayList<Rec>()
        for (c in heroes) {
            if (c.name in taken) continue
            if (s.lane != null && s.lane !in c.lanes) continue
            if (s.available != null && c.name !in s.available) continue

            val reasons = ArrayList<String>()
            var gain = 0.0; var risk = 0.0; var threats = 0
            for (e in s.enemies) {
                val eh = byName[e] ?: continue
                val g = max(eh.counteredBy.firstOrNull { it.hero == c.name }?.adv ?: 0.0,
                            c.strongAgainst.firstOrNull { it.hero == e }?.adv ?: 0.0)
                val r = max(c.counteredBy.firstOrNull { it.hero == e }?.adv ?: 0.0,
                            eh.strongAgainst.firstOrNull { it.hero == c.name }?.adv ?: 0.0)
                if (g > 0) { gain += g; reasons += "Counters $e (+%.1f)".format(g) }
                if (r > 0) { risk += r; threats++; reasons += "Weak to $e (-%.1f)".format(r) }
            }
            // being hit by several enemies hurts more than the sum
            risk *= 1.0 + 0.35 * (threats - 1).coerceAtLeast(0)

            // exposure to enemy picks that have not happened yet
            val exposure = c.counteredBy.sumOf { it.adv } * 0.12 * remaining / 5.0

            var meta = 0.0
            c.winRate?.let { meta += (it - 50.0) * 0.6 }
            meta += when (c.tier) { "S" -> 1.5; "A" -> 1.0; "B" -> 0.4; "D" -> -0.8; else -> 0.0 }
            if (c.tier != null && s.enemies.isEmpty()) reasons += "Meta tier ${c.tier}"

            val syn = allyHeroes.count { a -> c.synergies.contains(a.name) || a.synergies.contains(c.name) }
            if (syn > 0) reasons += "Combo with $syn ally"

            var comp = 0.0
            if ("Tank" in c.roles && "Tank" !in allyRoles) { comp += 1.0; reasons += "Team needs a tank" }
            if ("Marksman" in c.roles && "Marksman" in allyRoles) comp -= 1.5
            if ("Mage" in c.roles && allyRoles.count { it == "Mage" } >= 2) comp -= 1.0

            if (remaining > 0 && s.enemies.isEmpty() && reasons.none { it.startsWith("Meta") })
                reasons += "Hard to counter (low exposure)"

            val score = gain * 2.0 - risk * 2.5 - exposure + meta + syn * 1.5 + comp
            out += Rec(c, score, reasons.take(3))
        }
        return out.sortedByDescending { it.score }.take(top)
    }
}
