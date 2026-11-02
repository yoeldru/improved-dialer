// נכתב: 2026-11-01 10:55 (שעון ישראל) — חייגן משופר: התאמת חיוג קולי לאנשי קשר
// עודכן: 2026-11-01 20:10 — התאמה סלחנית יותר (כתיב חסר/מלא, אותיות דומות, מילות מילוי), מילת התעוררות גמישה
package com.yoeldru.dialer

import kotlin.math.max
import kotlin.math.min

object VoiceMatcher {

    private val commandWords = setOf(
        "חייג", "חייגי", "תחייג", "תחייגי", "לחייג", "חיוג", "חיג", "תחיג",
        "התקשר", "התקשרי", "תתקשר", "תתקשרי", "להתקשר",
        "צלצל", "תצלצל", "תצלצלי", "שיחה", "שיחת", "בבקשה", "אל", "היי", "הי",
        "call", "dial", "phone", "please"
    )

    /** מילים שלא שייכות לשם — מדלגים עליהן */
    private val fillerWords = setOf(
        "את", "של", "ל", "לי", "עכשיו", "בבקשה", "אל", "טלפון", "לטלפון", "נייד", "לנייד",
        "בית", "לבית", "הביתה", "מספר", "למספר", "הטלפון", "תודה", "אוקיי", "ok", "please", "to", "the"
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
            if (ch == '\'' || ch == '"' || ch == '׳' || ch == '״') continue // גרש/גרשיים: ג'ני -> גני
            if (ch.isLetterOrDigit()) sb.append(ch) else sb.append(' ')
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * "שלד" של מילה — מתעלם מהבדלי כתיב: בלי ו/י, וא/ה/ע נחשבים אותו דבר, וכך גם ק/כ, ט/ת, ס/ש, ב/ו.
     * כך "אושרה" = "אשרה" = "עושרא", "יעקב" = "יעקוב".
     */
    private fun skeleton(tok: String): String {
        val sb = StringBuilder()
        for ((i, raw) in tok.withIndex()) {
            val ch = when (raw) {
                'ו', 'י' -> if (i == 0) raw else continue
                'ה', 'ע' -> 'א'
                'ק' -> 'כ'
                'ט' -> 'ת'
                'ס' -> 'ש'
                else -> raw
            }
            if (sb.isNotEmpty() && sb.last() == ch) continue // אותיות כפולות
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun queryTokens(spoken: String): List<String> {
        val t = tokens(spoken).toMutableList()
        while (t.isNotEmpty() && (t[0] in commandWords || isWake(t[0]))) t.removeAt(0)
        return t.filter { it !in fillerWords && !it.all { ch -> ch.isDigit() } }
    }

    /** "לאושרה" -> גם "אושרה"; "ולמשה" -> "משה"; "שלמשה" -> "משה" */
    private fun variants(tok: String): List<String> {
        val v = mutableListOf(tok)
        for (prefix in listOf("ל", "ול", "של", "את", "ה")) {
            if (tok.length > prefix.length + 1 && tok.startsWith(prefix)) v.add(tok.substring(prefix.length))
        }
        return v
    }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
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
        if (q.length >= 2 && n.startsWith(q)) return 2.2
        if (n.length >= 2 && q.startsWith(n) && q.length - n.length <= 2) return 2.0
        val sq = skeleton(q)
        val sn = skeleton(n)
        if (sq.isNotEmpty() && sq == sn) return 2.5
        if (sq.length >= 2 && sn.startsWith(sq)) return 1.8
        val maxLen = max(q.length, n.length)
        val d = lev(q, n)
        if (maxLen >= 3 && d == 1) return 1.5
        val ds = lev(sq, sn)
        if (max(sq.length, sn.length) >= 3 && ds == 1) return 1.3
        if (maxLen >= 5 && d == 2) return 1.0
        return 0.0
    }

    private fun contactScore(query: List<String>, nameTokens: List<String>): Double {
        if (query.isEmpty() || nameTokens.isEmpty()) return 0.0
        var total = 0.0
        var matched = 0
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
            if (best > 0.0) {
                used.add(bestIdx)
                total += best
                matched++
            } else {
                total -= 0.7 // מילה שלא התאימה — קנס, אבל לא פוסל
            }
        }
        // לפחות מחצית מהמילים שנאמרו צריכות להתאים
        if (matched == 0 || matched * 2 < query.size) return 0.0
        if (matched == nameTokens.size) total += 0.6
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
        // גיבוי: השוואת כל המשפט לשם המלא (כשהזיהוי חיבר/פיצל מילים, למשל "אושרהראש")
        if (best.isEmpty()) {
            for (alt in spoken) {
                val joined = skeleton(queryTokens(alt).joinToString(""))
                if (joined.length < 3) continue
                for ((id, nt) in nameTokensById) {
                    val full = skeleton(nt.joinToString(""))
                    if (full.isEmpty()) continue
                    val d = lev(joined, full)
                    val limit = max(1, max(joined.length, full.length) / 4)
                    val s = when {
                        d == 0 -> 2.0
                        d <= limit -> 1.5 - d * 0.1
                        full.startsWith(joined) && joined.length >= 4 -> 1.0
                        else -> 0.0
                    }
                    if (s > 0 && s > (best[id] ?: 0.0)) best[id] = s
                }
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

    // ---------- מצב נהיגה ----------

    private val commandStarts = setOf(
        "חייג", "חייגי", "תחייג", "תחייגי", "חיג", "תחיג", "התקשר", "התקשרי", "תתקשר", "תתקשרי", "צלצל", "תצלצל"
    )

    /** איך זיהוי הדיבור עלול לכתוב "חייגן" */
    private val wakeForms = setOf("חייגנ", "חיגנ", "הייגנ", "היגנ", "חייגני", "חיגני", "חייגימ", "חגנ")
    private var wakeSkeleton = skeleton(normalize("חייגן"))
    private var customWakeTokens: List<String> = emptyList()
    private var customIsDefault = true

    /** קובע את מילת ההפעלה (מההגדרות). עודכן: 2026-11-02 20:15 */
    fun setWakeWord(word: String) {
        val t = tokens(word)
        customWakeTokens = t
        customIsDefault = t.size == 1 && skeleton(t[0]) == skeleton(normalize("חייגן"))
        wakeSkeleton = if (t.size == 1) skeleton(t[0]) else skeleton(normalize("חייגן"))
    }

    fun isWake(tok: String): Boolean {
        if (customIsDefault && tok in wakeForms) return true
        if (tok.length < 3) return false
        val s = skeleton(tok)
        return s == wakeSkeleton || (s.length >= 3 && lev(s, wakeSkeleton) <= 1 && s.startsWith("ח"))
    }

    /** האם המשפט מתחיל בפקודת חיוג ("חייג ל...") */
    fun startsWithCommand(s: String): Boolean {
        val t = tokens(s)
        return t.size >= 2 && t[0] in commandStarts
    }

    /** אמרו רק "חייג" — מתייחסים כמו "חייגן" */
    fun isBareCommand(spoken: List<String>): Boolean =
        spoken.any { val t = tokens(it); t.size == 1 && t[0] in commandStarts }

    /** אם נאמרה מילת ההפעלה — מחזיר את מה שנאמר אחריה (יכול להיות ריק). אחרת null */
    fun afterWakeWord(spoken: List<String>): List<String>? {
        val out = ArrayList<String>()
        for (alt in spoken) {
            val t = tokens(alt)
            if (customWakeTokens.size > 1) {
                // מילת הפעלה של כמה מילים, למשל "היי טלפון"
                val n = customWakeTokens.size
                for (i in 0..(t.size - n)) {
                    var ok = true
                    for (k in 0 until n) {
                        val a = skeleton(t[i + k])
                        val b = skeleton(customWakeTokens[k])
                        if (a != b && !(max(a.length, b.length) >= 3 && lev(a, b) <= 1)) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        out.add(t.drop(i + n).joinToString(" "))
                        break
                    }
                }
            } else {
                val i = t.indexOfFirst { isWake(it) }
                if (i >= 0) out.add(t.drop(i + 1).joinToString(" "))
            }
        }
        return if (out.isEmpty()) null else out
    }

    /** "אחת"/"שתיים"/... -> 0..3, "בטל" -> -2, אחרת -1 */
    fun parseChoice(spoken: List<String>): Int {
        for (alt in spoken) {
            for (t in tokens(alt)) {
                when (t) {
                    "1", "אחת", "אחד", "ראשון", "ראשונה", "הראשון", "הראשונה" -> return 0
                    "2", "שתיים", "שתים", "שניים", "שנים", "שני", "שנייה", "שניה", "השני", "השנייה" -> return 1
                    "3", "שלוש", "שלושה", "שלישי", "שלישית", "השלישי", "השלישית" -> return 2
                    "4", "ארבע", "ארבעה", "רביעי", "רביעית", "הרביעי" -> return 3
                    "בטל", "ביטול", "תבטל", "עזוב", "לא", "cancel" -> return -2
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
