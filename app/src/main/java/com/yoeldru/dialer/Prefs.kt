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
