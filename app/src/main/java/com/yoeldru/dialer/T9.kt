// נכתב: 2026-11-01 10:55 (שעון ישראל) — חייגן משופר: חיפוש T9 (עברית + אנגלית, 0 = רווח)
package com.yoeldru.dialer

object T9 {
    private val map = HashMap<Char, Char>()

    /** תוויות האותיות שמוצגות על המקשים */
    val keyLetters: Map<Char, String> = mapOf(
        '1' to "",
        '2' to "דהו · ABC",
        '3' to "אבג · DEF",
        '4' to "מנ · GHI",
        '5' to "יכל · JKL",
        '6' to "זחט · MNO",
        '7' to "רשת · PQRS",
        '8' to "צק · TUV",
        '9' to "סעפ · WXYZ",
        '0' to "רווח · +",
        '*' to "",
        '#' to ""
    )

    init {
        // פריסת T9 הישראלית הסטנדרטית
        put('2', "דהו" + "abc")
        put('3', "אבג" + "def")
        put('4', "מםנן" + "ghi")
        put('5', "יכךל" + "jkl")
        put('6', "זחט" + "mno")
        put('7', "רשת" + "pqrs")
        put('8', "צץק" + "tuv")
        put('9', "סעפף" + "wxyz")
    }

    private fun put(digit: Char, letters: String) {
        for (ch in letters) {
            map[ch] = digit
            map[ch.uppercaseChar()] = digit
        }
    }

    private fun charToDigit(ch: Char): Char? = if (ch in '0'..'9') ch else map[ch]

    /** כל מילה בשם הופכת לרצף ספרות */
    fun wordsToT9(name: String): List<String> {
        val words = name.split(' ', '-', '_', '.', ',', '(', ')', '"', '\'', '/')
        val out = ArrayList<String>()
        for (w in words) {
            val sb = StringBuilder()
            for (ch in w) {
                val d = charToDigit(ch)
                if (d != null) sb.append(d)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }
        return out
    }

    /**
     * ציון התאמה של שם לרצף שהוקלד. 0 = רווח בין מילים.
     * למשל "אושרה ראש": 32672 0 737 -> מתאים גם ל-"3267" או "3267207".
     * 3 = התאמה מתחילת השם, 2 = התאמה ממילה אחרת בשם, 0 = אין התאמה
     */
    fun matchScore(words: List<String>, query: String): Int {
        if (query.isEmpty() || query.startsWith("0")) return 0
        if (query.any { it == '*' || it == '#' || it == '+' }) return 0
        val parts = query.split('0').filter { it.isNotEmpty() }
        if (parts.isEmpty() || words.isEmpty()) return 0
        for (start in words.indices) {
            if (start + parts.size > words.size) break
            var ok = true
            for (k in parts.indices) {
                if (!words[start + k].startsWith(parts[k])) {
                    ok = false
                    break
                }
            }
            if (ok) return if (start == 0) 3 else 2
        }
        return 0
    }
}
