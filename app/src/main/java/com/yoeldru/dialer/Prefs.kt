// נכתב: 2026-11-01 11:00 (שעון ישראל) — חייגן משופר: הגדרות שמורות (הודעות מהירות)
package com.yoeldru.dialer

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val KEY_MESSAGES = "quick_messages"

    val defaultMessages = listOf(
        "אני בנהיגה, אחזור אליך בהקדם 🚗",
        "אני בשיעור, אחזור אליך אחר כך",
        "לא יכול לדבר כרגע, מה קורה?",
        "אני באמצע משהו, אחזור אליך עוד מעט"
    )

    fun quickMessages(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MESSAGES, null)
            ?: return defaultMessages
        val list = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) defaultMessages else list
    }

    fun setQuickMessages(ctx: Context, text: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MESSAGES, text).apply()
    }

    // נכתב: 2026-11-02 20:15 — מילת הפעלה וחיוג ישיר במצב נהיגה
    private const val KEY_WAKE = "wake_word"
    private const val KEY_DIRECT = "direct_commands"
    const val DEFAULT_WAKE = "חייגן"

    fun wakeWord(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_WAKE, DEFAULT_WAKE)?.trim()
            ?.ifEmpty { DEFAULT_WAKE } ?: DEFAULT_WAKE

    fun setWakeWord(ctx: Context, w: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_WAKE, w.trim()).apply()
    }

    /** "תתקשר לאושרה ראש" בלי מילת הפעלה */
    fun directCommands(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DIRECT, true)

    fun setDirectCommands(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIRECT, on).apply()
    }

    // נכתב: 2026-11-01 13:15 — סדר המועדפים שהמשתמש קבע
    private const val KEY_FAV_ORDER = "fav_order"

    fun favOrder(ctx: Context): List<Long> =
        (ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_FAV_ORDER, "") ?: "")
            .split(',').mapNotNull { it.trim().toLongOrNull() }

    fun setFavOrder(ctx: Context, ids: List<Long>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAV_ORDER, ids.joinToString(",")).apply()
    }
}
