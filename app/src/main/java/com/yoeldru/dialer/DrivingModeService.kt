// נכתב: 2026-11-01 12:55 (שעון ישראל) — חייגן משופר: מצב נהיגה — האזנה למילת "חייגן" וחיוג קולי בלי ידיים
package com.yoeldru.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class DrivingModeService : Service() {

    companion object {
        private const val ACTION_START = "com.yoeldru.dialer.DRIVE_START"
        private const val ACTION_STOP = "com.yoeldru.dialer.DRIVE_STOP"
        private const val CHANNEL = "driving_mode_v1"
        private const val NOTIF_ID = 3001

        @Volatile
        var running = false
            private set

        /** נקרא כשמצב הנהיגה נדלק/נכבה (לעדכון הכפתור במסך) */
        var onStateChanged: (() -> Unit)? = null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DrivingModeService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DrivingModeService::class.java))
        }
    }

    private enum class Mode { WAKE, NAME, CHOICE }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mode = Mode.WAKE
    private var choices: List<PhoneEntry> = emptyList()
    private var misses = 0
    private var pendingCall: String? = null
    private var utteranceCounter = 0
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!active) {
            active = true
            running = true
            onStateChanged?.invoke()
            setup()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "מצב נהיגה", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 30, Intent(this, DrivingModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 31, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentTitle("🚗 מצב נהיגה פעיל — מאזין")
            .setContentText("אמור: \"חייגן, חייג לאושרה ראש\"")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "עצור", stopPi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun setup() {
        Thread {
            if (ContactsRepo.phones.isEmpty()) ContactsRepo.load(applicationContext)
        }.start()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("he-IL")
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { afterSpeak() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { afterSpeak() }
            }
        })

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            PhoneUtils.toast(this, "זיהוי דיבור לא זמין במכשיר")
            stopSelf()
            return
        }
        createRecognizer()
        handler.postDelayed({ speak("מצב נהיגה פעיל. אמור חייגן, ואת שם איש הקשר") }, 600)
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
    }

    private fun listen() {
        if (!active) return
        handler.removeCallbacks(listenRunnable)
        // לא מאזינים בזמן שיחה
        if (CallManager.calls.isNotEmpty()) {
            handler.postDelayed(listenRunnable, 3000)
            return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            createRecognizer()
            handler.postDelayed(listenRunnable, 1000)
        }
    }

    private val listenRunnable = Runnable { listen() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    PhoneUtils.toast(this@DrivingModeService, "חסרה הרשאת מיקרופון")
                    stopSelf()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    handler.postDelayed({
                        createRecognizer()
                        listen()
                    }, 1500)
                }
                else -> {
                    // לא נשמע כלום — בשלב שאלה סופרים ניסיונות
                    if (mode != Mode.WAKE) {
                        misses++
                        if (misses >= 2) {
                            resetToWake()
                            speak("בוטל")
                            return
                        }
                    }
                    handler.postDelayed(listenRunnable, 250)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            handle(list)
        }
    }

    private fun handle(list: List<String>) {
        if (list.isEmpty()) {
            listen()
            return
        }
        when (mode) {
            Mode.WAKE -> {
                val afterWake = VoiceMatcher.afterWakeWord(list)
                val isCommand = list.any { VoiceMatcher.startsWithCommand(it) }
                if (afterWake == null && !isCommand) {
                    listen()
                    return
                }
                val texts = afterWake ?: list
                if (texts.all { VoiceMatcher.normalize(it).isEmpty() }) {
                    // אמרו רק "חייגן" — שואלים למי
                    mode = Mode.NAME
                    misses = 0
                    speak("למי לחייג?")
                    return
                }
                resolve(texts)
            }
            Mode.NAME -> resolve(list)
            Mode.CHOICE -> pickChoice(list)
        }
    }

    /** מספר מועדף לאיש קשר: נייד אם יש, אחרת הראשון */
    private fun preferred(entries: List<PhoneEntry>): PhoneEntry =
        entries.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE } ?: entries[0]

    private fun resolve(texts: List<String>) {
        if (VoiceMatcher.parseChoice(texts) == -2) {
            resetToWake()
            speak("בוטל")
            return
        }
        val matches = VoiceMatcher.match(texts, ContactsRepo.phones)
        val byContact = matches.groupBy { it.contactId }.values.map { preferred(it) }
        when {
            byContact.size == 1 -> callNow(byContact[0])
            byContact.size > 1 -> {
                choices = byContact.take(4)
                mode = Mode.CHOICE
                misses = 0
                val ordinals = listOf("אחת", "שתיים", "שלוש", "ארבע")
                val sb = StringBuilder("מצאתי ${choices.size}. ")
                choices.forEachIndexed { i, p -> sb.append(ordinals[i]).append(", ").append(p.name).append(". ") }
                sb.append("איזה מהם?")
                speak(sb.toString())
            }
            else -> {
                val num = VoiceMatcher.extractNumber(texts)
                if (num.length >= 9) {
                    pendingCall = num
                    resetToWake()
                    speak("מחייג למספר")
                    return
                }
                misses++
                if (misses >= 2 || mode == Mode.WAKE) {
                    resetToWake()
                    speak("לא מצאתי איש קשר מתאים")
                } else {
                    mode = Mode.NAME
                    speak("לא מצאתי. נסה שוב, למי לחייג?")
                }
            }
        }
    }

    private fun pickChoice(list: List<String>) {
        val c = VoiceMatcher.parseChoice(list)
        if (c == -2) {
            resetToWake()
            speak("בוטל")
            return
        }
        if (c in choices.indices) {
            callNow(choices[c])
            return
        }
        // אולי אמרו את השם
        val again = VoiceMatcher.match(list, choices).groupBy { it.contactId }.values.map { preferred(it) }
        if (again.size == 1) {
            callNow(again[0])
            return
        }
        misses++
        if (misses >= 2) {
            resetToWake()
            speak("בוטל")
        } else {
            speak("לא הבנתי. אמור אחת, שתיים או שלוש")
        }
    }

    private fun callNow(p: PhoneEntry) {
        pendingCall = p.number
        resetToWake()
        speak("מחייג ל" + p.name)
    }

    private fun resetToWake() {
        mode = Mode.WAKE
        choices = emptyList()
        misses = 0
    }

    private fun speak(text: String) {
        handler.removeCallbacks(listenRunnable)
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        val t = tts
        if (ttsReady && t != null) {
            utteranceCounter++
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u$utteranceCounter")
        } else {
            PhoneUtils.toast(this, text)
            handler.postDelayed({ afterSpeak() }, 800)
        }
    }

    private fun afterSpeak() {
        if (!active) return
        val n = pendingCall
        if (n != null) {
            pendingCall = null
            PhoneUtils.placeCall(this, n)
            handler.postDelayed(listenRunnable, 4000)
        } else {
            handler.postDelayed(listenRunnable, 300)
        }
    }

    override fun onDestroy() {
        active = false
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        tts?.shutdown()
        tts = null
        onStateChanged?.invoke()
        super.onDestroy()
    }
}
