// נכתב: 2026-11-02 20:35 (שעון ישראל) — חייגן משופר: הקראת שם המתקשר בקול בזמן שיחה נכנסת
// עודכן: 2026-11-08 11:00 — תיקון עיוות: לא מקריאים עברית בקול אנגלי, ניקוי השם, בחירת קול עברי איכותי,
//                          הגייה מותאמת לכל איש קשר והשמעת הקלטה בקולך
package com.yoeldru.dialer

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

object CallerAnnouncer {
    private const val FILE = "settings"
    private const val K_ON = "announce_on"
    private const val K_ONLY_EXT = "announce_only_external"
    private const val K_REPEAT = "announce_repeat"
    private const val K_VOICE = "announce_voice"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(K_ON, true)
    fun setEnabled(ctx: Context, v: Boolean) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(K_ON, v).apply()

    /** רק כשמחוברת דיבורית / אוזניות, או כשמצב נהיגה פעיל */
    fun onlyExternal(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(K_ONLY_EXT, false)
    fun setOnlyExternal(ctx: Context, v: Boolean) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(K_ONLY_EXT, v).apply()

    /** כמה פעמים להקריא (1–5) */
    fun repeats(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(K_REPEAT, 3)
    fun setRepeats(ctx: Context, v: Int) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(K_REPEAT, v).apply()

    /** הקול העברי שנבחר בהגדרות (null = אוטומטי) */
    fun voiceName(ctx: Context): String? = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(K_VOICE, null)
    fun setVoiceName(ctx: Context, v: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_VOICE, v).apply()
        applyVoice(ctx)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    /** מצב העברית במנוע: true = יש קול עברי מותקן */
    var hebrewAvailable = false
        private set
    private var pendingText: String? = null
    private var pendingRecording: File? = null
    private var englishOnly = false
    private var remaining = 0
    private var player: MediaPlayer? = null

    private val heb = Regex("[\\u05D0-\\u05EA]")

    /** מנקה את שם איש הקשר להקראה: בלי אימוג'י/סימנים, ואם יש עברית — בלי המילים באנגלית */
    fun spokenName(raw: String): String {
        var s = raw.replace(Regex("[^\\p{L}\\p{N}\\s'\"׳״-]"), " ")
        if (heb.containsMatchIn(s)) s = s.replace(Regex("\\b[A-Za-z][A-Za-z'.-]*\\b"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /** קורא מתי שנכנסת שיחה */
    fun onIncoming(ctx: Context, number: String) {
        if (!enabled(ctx)) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        // במצב שקט / רטט — לא מדברים
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        // באמצע שיחה אחרת (שיחה ממתינה) — לא מדברים
        if (CallManager.activeCall() != null) return
        if (onlyExternal(ctx) && !hasExternalAudio(am) && !DrivingModeService.running) return

        val info = ContactsRepo.lookup(ctx, number)
        val id = info.contactId
        pendingRecording = if (id != null && NameVoiceStore.hasRecording(ctx, id)) NameVoiceStore.recordingFile(ctx, id) else null
        val say = (id?.let { NameVoiceStore.pronunciation(ctx, it) }) ?: info.name?.let { spokenName(it) }
        val text = when {
            number.isBlank() -> "שיחה ממספר חסוי"
            say.isNullOrBlank() -> "שיחה ממספר לא מוכר"
            else -> "שיחה נכנסת. $say"
        }
        englishOnly = !say.isNullOrBlank() && !heb.containsMatchIn(say)
        pendingText = if (englishOnly) "Call from $say" else text
        remaining = repeats(ctx).coerceIn(1, 5)
        ensureTts(ctx.applicationContext)
        // השהיה קטנה — שהצלצול יתחיל קודם
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ speakNext() }, 1200)
    }

    /** עוצרים מיד (נענתה / נדחתה / הסתיימה) */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        remaining = 0
        pendingText = null
        pendingRecording = null
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        releasePlayer()
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    private fun stillRinging(): Boolean = CallManager.calls.any { CallManager.isRinging(it) }

    private fun speakNext() {
        val text = pendingText ?: return
        if (remaining <= 0 || !stillRinging()) {
            stop()
            return
        }
        remaining--
        // הקלטה בקולך — עדיפה על הקראה ממוחשבת
        val rec = pendingRecording
        if (rec != null && playFile(rec)) {
            if (remaining > 0) handler.postDelayed({ speakNext() }, 4500)
            return
        }
        val t = tts
        if (!ready || t == null) {
            remaining++
            handler.postDelayed({ speakNext() }, 400)
            return
        }
        if (englishOnly) {
            t.language = Locale.US
        } else {
            if (!hebrewAvailable) {
                // אין קול עברי: עדיף לא להקריא בכלל מאשר להקריא עברית בקול זר
                stop()
                return
            }
            applyVoice(null)
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller")
        if (remaining > 0) handler.postDelayed({ speakNext() }, 5000)
    }

    /** forTest = השמעה מהמסך (בעוצמת מדיה, כדי שבטוח יישמע). עודכן: 2026-11-08 11:40 */
    fun playFile(f: File, forTest: Boolean = false): Boolean {
        return try {
            releasePlayer()
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (forTest) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            false
        }
    }

    /** קולות עבריים זמינים, הטובים קודם */
    fun hebrewVoices(): List<Voice> {
        val t = tts ?: return emptyList()
        return try {
            (t.voices ?: emptySet()).filter {
                val l = it.locale.language
                (l == "he" || l == "iw") && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** בוחר את הקול שנבחר בהגדרות, או את הקול העברי האיכותי ביותר */
    private fun applyVoice(ctx: Context?) {
        val t = tts ?: return
        val voices = hebrewVoices()
        val wanted = ctx?.let { voiceName(it) } ?: savedVoice
        if (ctx != null) savedVoice = voiceName(ctx)
        val v = voices.firstOrNull { it.name == wanted } ?: voices.firstOrNull()
        if (v != null) {
            try {
                t.voice = v
                return
            } catch (e: Exception) {
            }
        }
        t.language = Locale.forLanguageTag("he-IL")
    }

    private var savedVoice: String? = null

    fun engineName(): String = try {
        tts?.defaultEngine ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun ensureTts(ctx: Context, onReady: (() -> Unit)? = null) {
        if (tts != null) {
            if (ready) onReady?.invoke() else handler.postDelayed({ ensureTts(ctx, onReady) }, 300)
            return
        }
        savedVoice = voiceName(ctx)
        val init = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val r = tts?.setLanguage(Locale.forLanguageTag("he-IL")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                hebrewAvailable = r >= TextToSpeech.LANG_AVAILABLE || hebrewVoices().isNotEmpty()
                applyVoice(null)
                ready = true
                handler.post { onReady?.invoke() }
            }
        }
        tts = try {
            TextToSpeech(ctx, init, "com.google.android.tts")
        } catch (e: Exception) {
            TextToSpeech(ctx, init)
        }
    }

    private fun hasExternalAudio(am: AudioManager): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

    /** משמיע טקסט לבדיקה (הגדרות / מסך הגייה) */
    fun say(ctx: Context, text: String) {
        ensureTts(ctx.applicationContext) {
            val t = tts ?: return@ensureTts
            if (!hebrewAvailable && heb.containsMatchIn(text)) {
                PhoneUtils.toast(ctx, "אין קול עברי מותקן — ראה הגדרות ← הקראת שם המתקשר")
                return@ensureTts
            }
            if (heb.containsMatchIn(text)) applyVoice(null) else t.language = Locale.US
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    /** בדיקה מההגדרות */
    fun test(ctx: Context) = say(ctx, "שיחה נכנסת. אושרה ראש")

    /** פתיחת מסך התקנת קולות (Speech Services by Google) */
    fun installVoicesIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
}
