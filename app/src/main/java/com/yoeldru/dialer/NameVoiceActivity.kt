// נכתב: 2026-11-08 11:10 (שעון ישראל) — חייגן משופר: מסך "הגייה, הקלטה וזיהוי קולי" לאיש קשר
package com.yoeldru.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class NameVoiceActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"

        fun open(ctx: Context, contactId: Long, name: String) {
            ctx.startActivity(
                Intent(ctx, NameVoiceActivity::class.java)
                    .putExtra(EXTRA_ID, contactId).putExtra(EXTRA_NAME, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private var contactId = 0L
    private var name = ""
    private lateinit var pronEdit: EditText
    private lateinit var recStatus: TextView
    private lateinit var recBtn: MaterialButton
    private lateinit var learned: TextView
    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else PhoneUtils.toast(this, "צריך הרשאת מיקרופון")
    }

    private val learnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: return@registerForActivityResult
        NameVoiceStore.addAliases(this, contactId, list)
        PhoneUtils.toast(this, "נשמר: \"${list.firstOrNull() ?: ""}\"")
        refreshLearned()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent.getLongExtra(EXTRA_ID, 0L)
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        CallerAnnouncer.ensureTts(applicationContext)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { fitsSystemWindows = true }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(title("🗣 $name", 22f))

        // ---- 1. הגייה ----
        col.addView(title("איך להקריא את השם", 18f, top = true))
        col.addView(hint("כתוב את השם כמו שהוא נשמע. ניקוד עוזר מאוד לקול העברי, למשל: אַיָּלָה. אפשר גם \"אשתי\" או כל כינוי."))
        pronEdit = EditText(this).apply {
            setText(NameVoiceStore.pronunciation(this@NameVoiceActivity, contactId) ?: CallerAnnouncer.spokenName(name))
        }
        col.addView(pronEdit)
        col.addView(row(
            button("🔊 השמע") { CallerAnnouncer.say(this, "שיחה נכנסת. " + pronEdit.text.toString()) },
            button("💾 שמור") {
                NameVoiceStore.setPronunciation(this, contactId, pronEdit.text.toString())
                PhoneUtils.toast(this, "נשמר")
            },
            button("איפוס") {
                NameVoiceStore.setPronunciation(this, contactId, null)
                pronEdit.setText(CallerAnnouncer.spokenName(name))
            }
        ))

        // ---- 2. הקלטה בקולך ----
        col.addView(title("הקלטה בקולך", 18f, top = true))
        col.addView(hint("ההקלטה תושמע בשיחה נכנסת במקום ההקראה הממוחשבת. למשל: \"אילה מתקשרת\". עד 5 שניות."))
        recStatus = hint("")
        col.addView(recStatus)
        recBtn = button("⏺ הקלט") { toggleRecording() }
        col.addView(row(
            recBtn,
            button("▶ השמע") {
                val f = NameVoiceStore.recordingFile(this, contactId)
                if (!NameVoiceStore.hasRecording(this, contactId) || !CallerAnnouncer.playFile(f)) PhoneUtils.toast(this, "אין הקלטה")
            },
            button("🗑 מחק") {
                NameVoiceStore.recordingFile(this, contactId).delete()
                refreshRecStatus()
            }
        ))

        // ---- 3. לימוד זיהוי קולי ----
        col.addView(title("לימוד זיהוי קולי", 18f, top = true))
        col.addView(hint("לחץ \"למד\" ואמור את השם בדיוק כמו שאתה אומר אותו בחיוג קולי. מה שהטלפון ישמע יישמר, ובפעם הבאה הוא יזהה את זה כאיש הקשר הזה. מומלץ 2–3 פעמים."))
        learned = hint("")
        col.addView(learned)
        col.addView(row(
            button("🎤 למד") {
                try {
                    learnLauncher.launch(SpeechHelper.activityIntent(this, "אמור: $name"))
                } catch (e: Exception) {
                    PhoneUtils.toast(this, "זיהוי דיבור לא זמין")
                }
            },
            button("נקה") {
                NameVoiceStore.clearAliases(this, contactId)
                refreshLearned()
            }
        ))

        refreshRecStatus()
        refreshLearned()
    }

    override fun onStop() {
        stopRecording()
        super.onStop()
    }

    private fun refreshRecStatus() {
        recStatus.text = if (NameVoiceStore.hasRecording(this, contactId)) "✔ יש הקלטה" else "אין הקלטה — תושמע הקראה ממוחשבת"
    }

    private fun refreshLearned() {
        val list = NameVoiceStore.aliases(this, contactId)
        learned.text = if (list.isEmpty()) "עדיין לא נלמד" else "נלמד:\n" + list.joinToString("\n") { "• $it" }
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        try {
            val f = NameVoiceStore.recordingFile(this, contactId)
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recBtn.text = "⏹ עצור"
            recStatus.text = "🔴 מקליט... דבר עכשיו"
            handler.postDelayed({ stopRecording() }, 5000)
        } catch (e: Exception) {
            PhoneUtils.toast(this, "ההקלטה נכשלה")
            recorder = null
        }
    }

    private fun stopRecording() {
        handler.removeCallbacksAndMessages(null)
        val r = recorder ?: return
        try {
            r.stop()
        } catch (e: Exception) {
            NameVoiceStore.recordingFile(this, contactId).delete()
        }
        r.release()
        recorder = null
        recBtn.text = "⏺ הקלט"
        refreshRecStatus()
    }

    // ---- עזרי תצוגה ----

    private fun title(t: String, size: Float, top: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        if (top) setPadding(0, (22 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        alpha = 0.75f
    }

    private fun button(t: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            addView(v, lp)
        }
    }
}
