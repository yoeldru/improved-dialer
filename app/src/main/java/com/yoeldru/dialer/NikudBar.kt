// נכתב: 2026-11-08 11:45 (שעון ישראל) — חייגן משופר: שורת כפתורי ניקוד מתחת לשדה טקסט
package com.yoeldru.dialer

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

object NikudBar {

    /** סימן ניקוד + שם (מוצג בלחיצה ארוכה) */
    private val marks = listOf(
        "ָ" to "קָמָץ",
        "ַ" to "פַּתָּח",
        "ֵ" to "צֵירֵה",
        "ֶ" to "סֶגּוֹל",
        "ִ" to "חִירִיק",
        "ֹ" to "חוֹלָם",
        "ֻ" to "קֻבּוּץ",
        "ּ" to "דָּגֵשׁ / שׁוּרוּק",
        "ְ" to "שְׁוָא",
        "ֲ" to "חֲטַף פַּתָּח",
        "ֱ" to "חֱטַף סֶגּוֹל",
        "ֳ" to "חֳטַף קָמָץ",
        "ׁ" to "שִׁין יְמָנִית",
        "ׂ" to "שִׂין שְׂמָאלִית"
    )

    /** בונה שורה נגללת של כפתורי ניקוד שמוסיפים את הסימן אחרי האות שלפני הסמן */
    fun create(ctx: Context, target: EditText): HorizontalScrollView {
        val d = ctx.resources.displayMetrics.density
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(row)

        for ((mark, label) in marks) {
            val b = TextView(ctx).apply {
                // מציגים את הסימן על אות ב' כדי שיהיה ברור איך הוא נראה
                text = "ב$mark"
                textSize = 26f
                typeface = Typeface.SERIF
                gravity = Gravity.CENTER
                minWidth = (48 * d).toInt()
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
                background = ctx.getDrawable(R.drawable.display_frame)
                contentDescription = label
                setOnClickListener { insert(target, mark) }
                setOnLongClickListener {
                    PhoneUtils.toast(ctx, label)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (52 * d).toInt())
            lp.marginEnd = (6 * d).toInt()
            lp.topMargin = (6 * d).toInt()
            row.addView(b, lp)
        }
        // מחיקת סימן אחרון
        val del = TextView(ctx).apply {
            text = "⌫"
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = (48 * d).toInt()
            background = ctx.getDrawable(R.drawable.display_frame)
            contentDescription = "מחק"
            setOnClickListener {
                val s = target.selectionStart.coerceAtLeast(0)
                if (s > 0) target.text.delete(s - 1, s)
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (52 * d).toInt())
        lp.topMargin = (6 * d).toInt()
        row.addView(del, lp)
        return scroll
    }

    private fun insert(target: EditText, mark: String) {
        val start = target.selectionStart.coerceAtLeast(0)
        val end = target.selectionEnd.coerceAtLeast(start)
        target.text.replace(start, end, mark)
        target.setSelection((start + mark.length).coerceAtMost(target.text.length))
        target.requestFocus()
    }
}
