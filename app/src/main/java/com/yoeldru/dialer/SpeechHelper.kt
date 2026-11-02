// נכתב: 2026-11-01 20:15 (שעון ישראל) — חייגן משופר: בחירת מנוע זיהוי דיבור שתומך בעברית (עדיפות לגוגל)
package com.yoeldru.dialer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

object SpeechHelper {
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val GOOGLE_TTS = "com.google.android.tts"

    /** מנועי זיהוי דיבור שמותקנים במכשיר */
    private fun services(ctx: Context): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL)
            .mapNotNull { ri -> ri.serviceInfo?.let { ComponentName(it.packageName, it.name) } }
    } catch (e: Exception) {
        emptyList()
    }

    /** מנוע של גוגל אם קיים (מנוע של סמסונג לעיתים לא מזהה עברית) */
    fun googleComponent(ctx: Context): ComponentName? {
        val all = services(ctx)
        return all.firstOrNull { it.packageName == GOOGLE_APP }
            ?: all.firstOrNull { it.packageName == GOOGLE_TTS }
    }

    fun create(ctx: Context, preferGoogle: Boolean): SpeechRecognizer {
        val comp = if (preferGoogle) googleComponent(ctx) else null
        return if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    fun hebrewExtras(i: Intent): Intent = i.apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL")
        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("iw-IL"))
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    /**
     * "מכוון" את זיהוי הדיבור לשמות מאנשי הקשר ולמילת ההפעלה (אנדרואיד 13+).
     * נכתב: 2026-11-02 20:20
     */
    fun addBiasing(ctx: Context, i: Intent) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val words = LinkedHashSet<String>()
        words.add(Prefs.wakeWord(ctx))
        words.addAll(listOf("חייג", "תתקשר", "התקשר"))
        val phones = ContactsRepo.phones
        // קודם מועדפים, אחר כך כל השאר
        phones.filter { it.starred }.forEach { words.add(it.name) }
        for (p in phones) {
            if (words.size >= 150) break
            words.add(p.name)
        }
        i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words.filter { it.isNotBlank() }))
    }

    /** מסך "דבר עכשיו" — עדיפות לגוגל */
    fun activityIntent(ctx: Context, prompt: String): Intent {
        val i = hebrewExtras(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        addBiasing(ctx, i)
        val google = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setPackage(GOOGLE_APP)
        if (ctx.packageManager.resolveActivity(google, 0) != null) i.setPackage(GOOGLE_APP)
        return i
    }
}
