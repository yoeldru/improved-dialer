// נכתב: 2026-11-01 10:55 (שעון ישראל) — חייגן משופר: התאמת חיוג קולי לאנשי קשר
package com.yoeldru.dialer

import kotlin.math.max
import kotlin.math.min

object VoiceMatcher {

    private val commandWords = setOf(
        "חייג", "חייגי", "תחייג", "תחייגי", "לחייג", "חיוג",
        "התקשר", "התקשרי", "תתקשר", "תתקשרי", "להתקשר",
        "צלצל", "תצלצל", "תצלצלי", "שיחה", "בבקשה", "אל",
        "call", "dial", "phone", "please"
    )

    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (raw in s.lowercase()) {
            val ch = when (raw) {
                'ך' -> 'כ'
                'ם' -> 'מ'
                'ן' -> 'נ'
                'ף' -> 'פ'
                'ץ' -> 'צ'
                else -> raw
            }
            if (ch in '֑'..'ׇ') continue // ניקוד וטעמים
            if (ch.isLetterOrDigit()) sb.append(ch) else sb.append(' ')
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    private fun queryTokens(spoken: String): List<String> {
        val t = tokens(spoken).toMutableList()
        while (t.isNotEmpty() && t[0] in commandWords) t.removeAt(0)
        return t.filter { it != "בבקשה" && it != "please" && !it.all { ch -> ch.isDigit() } }
    }

    /** "לאושרה" -> גם "אושרה" */
    private fun variants(tok: String): List<String> {
        val v = mutableListOf(tok)
        if (tok.length > 2 && tok[0] == 'ל') v.add(tok.substring(1))
        return v
    }

    private fun lev(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun tokenScore(q: String, n: String): Double {
        if (q == n) return 3.0
        if (q.length >= 2 && n.startsWith(q)) return 2.0
        if (n.length >= 2 && q.startsWith(n) && q.length - n.length <= 2) return 2.0
        val maxLen = max(q.length, n.length)
        val d = lev(q, n)
        if (maxLen >= 3 && d == 1) return 1.5
        if (maxLen >= 5 && d == 2) return 1.0
        return 0.0
    }

    private fun contactScore(query: List<String>, nameTokens: List<String>): Double {
        if (query.isEmpty() || nameTokens.isEmpty()) return 0.0
        var total = 0.0
        val used = HashSet<Int>()
        for (q in query) {
            var best = 0.0
            var bestIdx = -1
            for (v in variants(q)) {
                for ((i, n) in nameTokens.withIndex()) {
                    if (i in used) continue
                    val s = tokenScore(v, n)
                    if (s > best) {
                        best = s
                        bestIdx = i
                    }
                }
            }
            if (best == 0.0) return 0.0 // כל מילה שנאמרה חייבת להתאים
            used.add(bestIdx)
            total += best
        }
        if (query.size == nameTokens.size) total += 0.5
        return total
    }

    /**
     * מחזיר את מספרי הטלפון של אנשי הקשר המתאימים ביותר.
     * spoken = כל החלופות שזיהוי הדיבור החזיר.
     */
    fun match(spoken: List<String>, phones: List<PhoneEntry>): List<PhoneEntry> {
        val nameTokensById = HashMap<Long, List<String>>()
        for (p in phones) nameTokensById.getOrPut(p.contactId) { tokens(p.name) }

        val best = HashMap<Long, Double>()
        for (alt in spoken) {
            val q = queryTokens(alt)
            if (q.isEmpty()) continue
            for ((id, nt) in nameTokensById) {
                val s = contactScore(q, nt)
                if (s > 0 && s > (best[id] ?: 0.0)) best[id] = s
            }
        }
        if (best.isEmpty()) return emptyList()
        val top = best.values.maxOrNull() ?: return emptyList()
        val chosen = best.filter { it.value >= top - 1.0 }
            .entries.sortedByDescending { it.value }
            .map { it.key }
        val out = ArrayList<PhoneEntry>()
        for (id in chosen) out.addAll(phones.filter { it.contactId == id })
        return out
    }

    // נכתב: 2026-11-01 12:50 — עזרים למצב נהיגה
    private val commandStarts = setOf(
        "חייג", "חייגי", "תחייג", "תחייגי", "התקשר", "התקשרי", "תתקשר", "תתקשרי", "צלצל", "תצלצל"
    )
    private val wakeWord = normalize("חייגן")

    /** האם המשפט מתחיל בפקודת חיוג ("חייג ל...") */
    fun startsWithCommand(s: String): Boolean {
        val t = tokens(s)
        return t.isNotEmpty() && t[0] in commandStarts && t.size >= 2
    }

    private fun isWake(tok: String): Boolean = tok == wakeWord || (tok.length >= 4 && lev(tok, wakeWord) <= 1)

    /** אם נאמרה מילת ההתעוררות "חייגן" — מחזיר את מה שנאמר אחריה (יכול להיות ריק). אחרת null */
    fun afterWakeWord(spoken: List<String>): List<String>? {
        val out = ArrayList<String>()
        for (alt in spoken) {
            val t = tokens(alt)
            val i = t.indexOfFirst { isWake(it) }
            if (i >= 0) out.add(t.drop(i + 1).joinToString(" "))
        }
        return if (out.isEmpty()) null else out
    }

    /** "אחת"/"שתיים"/... -> 0..3, "בטל" -> -2, אחרת -1 */
    fun parseChoice(spoken: List<String>): Int {
        for (alt in spoken) {
            for (t in tokens(alt)) {
                when (t) {
                    "1", "אחת", "אחד", "ראשון", "ראשונה" -> return 0
                    "2", "שתיים", "שתים", "שניים", "שנים", "שני", "שנייה", "שניה" -> return 1
                    "3", "שלוש", "שלושה", "שלישי", "שלישית" -> return 2
                    "4", "ארבע", "ארבעה", "רביעי", "רביעית" -> return 3
                    "בטל", "ביטול", "עזוב", "לא", "cancel" -> return -2
                }
            }
        }
        return -1
    }

    /** "חייג ל 050 123 4567" -> 0501234567 */
    fun extractNumber(spoken: List<String>): String {
        for (alt in spoken) {
            val d = alt.filter { it.isDigit() }
            if (d.length >= 3) return d
        }
        return ""
    }
}
