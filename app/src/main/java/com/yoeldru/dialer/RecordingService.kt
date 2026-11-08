// נכתב: 2026-11-01 22:50 (שעון ישראל) — חייגן משופר: הקלטת שיחות (שמירה בתיקיית Recordings/ImprovedDialer)
package com.yoeldru.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        private const val EXTRA_NAME = "name"
        private const val CHANNEL = "recording_v1"
        private const val NOTIF_ID = 4001

        val FOLDER: String
            get() = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Environment.DIRECTORY_RECORDINGS
            else Environment.DIRECTORY_MUSIC) + "/ImprovedDialer"

        @Volatile
        var recording = false
            private set

        @Volatile
        var startedAt = 0L
            private set

        /** נקרא כשנראה שהמכשיר חוסם את ההקלטה (שקט מוחלט) */
        var onSilenceDetected: (() -> Unit)? = null

        fun start(ctx: Context, name: String) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, RecordingService::class.java).putExtra(EXTRA_NAME, name)
            )
        }

        fun stop(ctx: Context) {
            if (recording) ctx.stopService(Intent(ctx, RecordingService::class.java))
        }
    }

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())
    private var maxAmp = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recording) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "הקלטת שיחה", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentTitle("⏺ מקליט שיחה")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            PhoneUtils.toast(this, "לא ניתן להתחיל הקלטה")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!begin(intent?.getStringExtra(EXTRA_NAME) ?: "שיחה")) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { "שיחה" }.take(40)

    // ---------- עודכן: 2026-11-08 12:05 — ניסיון כמה מקורות שמע, ומעבר אוטומטי אם ההקלטה שקטה ----------
    private var recName = "שיחה"
    private var sourceIndex = 0

    /** סדר הניסיון: קו השיחה (שני הצדדים) ← זיהוי דיבור ← תקשורת ← מיקרופון */
    private val sources = intArrayOf(
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC
    )

    /** איזה מקור עבד בפעם הקודמת — מתחילים ממנו */
    private fun savedSource(): Int = getSharedPreferences("settings", MODE_PRIVATE).getInt("rec_source_idx", 0)
    private fun saveSource(i: Int) = getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("rec_source_idx", i).apply()

    private fun begin(name: String): Boolean {
        recName = name
        sourceIndex = savedSource().coerceIn(0, sources.size - 1)
        return startWithSource()
    }

    private fun newOutput(): Boolean {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safeName(recName) + "_" + stamp + ".m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val u = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        uri = u
        pfd = contentResolver.openFileDescriptor(u, "w") ?: return false
        return true
    }

    /** מנסה מקורות לפי הסדר עד שאחד מתחיל להקליט */
    private fun startWithSource(): Boolean {
        var lastError: String? = null
        while (sourceIndex < sources.size) {
            try {
                if (!newOutput()) return false
                val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
                r.setAudioSource(sources[sourceIndex])
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(96000)
                r.setOutputFile(pfd!!.fileDescriptor)
                r.prepare()
                r.start()
                recorder = r
                recording = true
                startedAt = System.currentTimeMillis()
                CallManager.notifyChanged()
                // בדיקה שבאמת מגיע קול (בחלק מהמכשירים המקור "מצליח" אבל מקליט שקט)
                maxAmp = 0
                ampChecks = 0
                handler.removeCallbacks(ampCheck)
                handler.postDelayed(ampCheck, 500)
                return true
            } catch (e: Exception) {
                lastError = e.message
                cleanupFailed()
                sourceIndex++
            }
        }
        PhoneUtils.toast(this, "ההקלטה נכשלה" + (lastError?.let { ": $it" } ?: ""))
        return false
    }

    /** המקור הנוכחי מקליט שקט — עוברים לבא בתור */
    private fun switchSource() {
        handler.removeCallbacks(ampCheck)
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        cleanupFailed()
        sourceIndex++
        if (sourceIndex >= sources.size || !startWithSource()) {
            recording = false
            CallManager.notifyChanged()
            onSilenceDetected?.invoke()
            stopSelf()
        }
    }

    private var ampChecks = 0
    private val ampCheck = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            try {
                maxAmp = maxOf(maxAmp, r.maxAmplitude)
            } catch (e: Exception) {
            }
            ampChecks++
            if (maxAmp > 0) {
                // יש קול — זוכרים את המקור שעבד לפעם הבאה
                saveSource(sourceIndex)
                return
            }
            if (ampChecks < 6) {
                handler.postDelayed(this, 500)
            } else {
                switchSource() // 3 שניות של שקט מוחלט — המקור חסום
            }
        }
    }

    private fun cleanupFailed() {
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        uri?.let {
            try {
                contentResolver.delete(it, null, null)
            } catch (e: Exception) {
            }
        }
        uri = null
    }

    /** התראה "ההקלטה נשמרה" — הקשה פותחת את רשימת ההקלטות */
    private fun notifySaved() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val pi = android.app.PendingIntent.getActivity(
                this, 41, Intent(this, RecordingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif_call)
                .setContentTitle("📼 הקלטת השיחה נשמרה")
                .setContentText("הקש כדי לשמוע, לשתף או למחוק")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID + 1, n)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val r = recorder
        if (r != null) {
            try {
                r.stop()
            } catch (e: Exception) {
            }
            r.release()
        }
        recorder = null
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        uri?.let {
            try {
                val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                contentResolver.update(it, v, null, null)
                PhoneUtils.toast(this, "ההקלטה נשמרה")
                notifySaved() // נכתב: 2026-11-08 11:50 — התראה עם קישור להשמעה
            } catch (e: Exception) {
            }
        }
        val was = recording
        recording = false
        startedAt = 0L
        if (was) CallManager.notifyChanged()
        super.onDestroy()
    }
}
