// נכתב: 2026-11-08 10:45 (שעון ישראל) — חייגן משופר: הגייה מותאמת, הקלטת שם בקולי, "לימוד" זיהוי קולי, ושמות למספרים לא שמורים
package com.yoeldru.dialer

import android.content.Context
import java.io.File

object NameVoiceStore {
    private const val FILE = "name_voice"
    private const val LABELS = "number_labels"

    // ---------- הגייה ----------

    /** איך להקריא את השם (למשל עם ניקוד: "אַיָּלָה") */
    fun pronunciation(ctx: Context, contactId: Long): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("pron_$contactId", null)?.takeIf { it.isNotBlank() }

    fun setPronunciation(ctx: Context, contactId: Long, text: String?) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (text.isNullOrBlank()) e.remove("pron_$contactId") else e.putString("pron_$contactId", text.trim())
        e.apply()
    }

    // ---------- הקלטת שם ----------

    fun recordingFile(ctx: Context, contactId: Long): File = File(File(ctx.filesDir, "names").apply { mkdirs() }, "$contactId.m4a")

    fun hasRecording(ctx: Context, contactId: Long): Boolean = recordingFile(ctx, contactId).let { it.exists() && it.length() > 1000 }

    // ---------- לימוד זיהוי קולי ----------

    /** מה שזיהוי הדיבור שמע כשאמרת את השם — נשמר כדי לזהות בפעם הבאה */
    fun aliases(ctx: Context, contactId: Long): List<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet("alias_$contactId", emptySet())?.toList() ?: emptyList()

    fun addAliases(ctx: Context, contactId: Long, heard: List<String>) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val set = (sp.getStringSet("alias_$contactId", emptySet()) ?: emptySet()).toMutableSet()
        for (h in heard) {
            val n = VoiceMatcher.normalize(h)
            if (n.isNotBlank()) set.add(n)
        }
        sp.edit().putStringSet("alias_$contactId", set).apply()
        VoiceMatcher.aliases = allAliases(ctx)
    }

    fun clearAliases(ctx: Context, contactId: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("alias_$contactId").apply()
        VoiceMatcher.aliases = allAliases(ctx)
    }

    fun allAliases(ctx: Context): Map<Long, List<String>> {
        val out = HashMap<Long, List<String>>()
        for ((k, v) in ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).all) {
            if (!k.startsWith("alias_")) continue
            val id = k.removePrefix("alias_").toLongOrNull() ?: continue
            @Suppress("UNCHECKED_CAST")
            val list = (v as? Set<String>)?.toList() ?: continue
            if (list.isNotEmpty()) out[id] = list
        }
        return out
    }

    // ---------- שם למספר לא שמור ----------

    fun label(ctx: Context, number: String): String? {
        val key = PhoneUtils.localDigits(number)
        if (key.length < 5) return null
        return ctx.getSharedPreferences(LABELS, Context.MODE_PRIVATE).getString(key, null)?.takeIf { it.isNotBlank() }
    }

    fun setLabel(ctx: Context, number: String, name: String?) {
        val key = PhoneUtils.localDigits(number)
        if (key.length < 5) return
        val e = ctx.getSharedPreferences(LABELS, Context.MODE_PRIVATE).edit()
        if (name.isNullOrBlank()) e.remove(key) else e.putString(key, name.trim())
        e.apply()
        ContactsRepo.clearLookupCache()
    }
}
