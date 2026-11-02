// נכתב: 2026-11-02 20:35 (שעון ישראל) — חייגן משופר: הקראת שם המתקשר בקול בזמן שיחה נכנסת
package com.yoeldru.dialer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

object CallerAnnouncer {
    private const val FILE = "settings"
    private const val K_ON = "announce_on"
    private const val K_ONLY_EXT = "announce_only_external"
    private const val K_REPEAT = "announce_repeat"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(K_ON, true)
    fun setEnabled(ctx: Context, v: Boolean) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(K_ON, v).apply()

    /** רק כשמחוברת דיבורית / אוזניות, או כשמצב נהיגה פעיל */
    fun onlyExternal(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(K_ONLY_EXT, false)
    fun setOnlyExternal(ctx: Context, v: Boolean) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(K_ONLY_EXT, v).apply()

    /** כמה פעמים להקריא (1–5) */
    fun repeats(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(K_REPEAT, 3)
    fun setRepeats(ctx: Context, v: Int) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(K_REPEAT, v).apply()

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var hasLatin = false
    private var remaining = 0
    private var appCtx: Context? = null

    /** קורא מתי שנכנסת שיחה */
    fun onIncoming(ctx: Context, number: String) {
        if (!enabled(ctx)) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        // במצב שקט / רטט — לא מדברים
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        // באמצע שיחה אחרת (שיחה ממתינה) — לא מדברים
        if (CallManager.activeCall() != null) return
        if (onlyExternal(ctx) && !hasExternalAudio(am) && !DrivingModeService.running) return

        val name = ContactsRepo.lookup(ctx, number).name
        val text = when {
            number.isBlank() -> "שיחה ממספר חסוי"
            name.isNullOrBlank() -> "שיחה ממספר לא מוכר"
            else -> "שיחה מ$name"
        }
        hasLatin = name?.any { it in 'a'..'z' || it in 'A'..'Z' } == true
        appCtx = ctx.applicationContext
        pendingText = text
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
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
    }

    private fun stillRinging(): Boolean = CallManager.calls.any { CallManager.isRinging(it) }

    private fun speakNext() {
        val text = pendingText ?: return
        if (remaining <= 0 || !stillRinging()) {
            stop()
            return
        }
        val t = tts
        if (!ready || t == null) {
            // ה-TTS עוד לא מוכן — ננסה שוב בעוד רגע
            handler.postDelayed({ speakNext() }, 400)
            return
        }
        remaining--
        // שם באנגלית — הקול האנגלי מבטא טוב יותר
        if (hasLatin) {
            t.language = Locale.US
        } else {
            t.language = Locale.forLanguageTag("he-IL")
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller")
        if (remaining > 0) handler.postDelayed({ speakNext() }, 5000)
    }

    private fun ensureTts(ctx: Context) {
        if (tts != null) return
        val init = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setLanguage(Locale.forLanguageTag("he-IL"))
                ready = true
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

    /** בדיקה מההגדרות */
    fun test(ctx: Context) {
        ensureTts(ctx.applicationContext)
        handler.postDelayed({
            val t = tts
            if (ready && t != null) {
                t.language = Locale.forLanguageTag("he-IL")
                t.speak("שיחה מאושרה ראש", TextToSpeech.QUEUE_FLUSH, null, "test")
            } else {
                PhoneUtils.toast(ctx, "אין קול עברי זמין במכשיר")
            }
        }, if (ready) 0 else 1200)
    }
}
