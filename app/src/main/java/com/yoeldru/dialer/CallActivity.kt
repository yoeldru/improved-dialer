// נכתב: 2026-11-01 11:15 (שעון ישראל) — חייגן משופר: מסך שיחה נכנסת/פעילה, דחייה עם וואטסאפ/SMS, בחירת SIM
package com.yoeldru.dialer

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ANSWER = "answer"

        // נכתב: 2026-11-05 19:15 — כשמסך השיחה מוצג, בלי ההתראה הקטנה שקופצת מעליו
        @Volatile
        var visible = false

        @Volatile
        var launchedAt = 0L
    }

    private lateinit var photo: ImageView
    private lateinit var initial: TextView
    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var statusView: TextView
    private lateinit var extraView: TextView
    private lateinit var incomingPanel: View
    private lateinit var ongoingPanel: View
    private lateinit var dtmfKeypad: LinearLayout
    private lateinit var btnMute: MaterialButton
    private lateinit var btnSpeaker: MaterialButton
    private lateinit var btnHold: MaterialButton
    private lateinit var btnSwap: MaterialButton
    private lateinit var btnMerge: MaterialButton
    private lateinit var btnRecord: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private var shownNumber: String? = null
    private var accountDialogShown = false
    private var keepOpen = false        // לא לסגור בזמן שפותחים וואטסאפ
    private var finishing = false
    private var dtmfTyped = StringBuilder()
    private var proximityLock: PowerManager.WakeLock? = null

    private val listener: () -> Unit = { runOnUiThread { render() } }

    // ---------- נכתב: 2026-11-02 20:35 — מעבר לרקע אחרי כמה שניות (וויז / ניווט) ----------
    private var autoBackScheduledFor: android.telecom.Call? = null
    private var userTouched = false
    private val goBackRunnable = Runnable {
        if (!userTouched && !isFinishing && CallManager.primary() != null &&
            CallManager.primary()?.let { CallManager.isRinging(it) } != true
        ) {
            moveTaskToBack(true)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // המשתמש נוגע במסך — לא מעבירים לרקע
        userTouched = true
        handler.removeCallbacks(goBackRunnable)
    }

    private fun maybeScheduleAutoBack(call: android.telecom.Call, state: Int) {
        if (autoBackScheduledFor === call) return
        val outgoingStarted = state == Call.STATE_DIALING || state == Call.STATE_CONNECTING
        val answered = state == Call.STATE_ACTIVE
        if (!outgoingStarted && !answered) return
        if (!AutoBackground.shouldGoBack(this)) return
        autoBackScheduledFor = call
        userTouched = false
        handler.removeCallbacks(goBackRunnable)
        handler.postDelayed(goBackRunnable, AutoBackground.seconds(this) * 1000L)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_call)

        photo = findViewById(R.id.photo)
        initial = findViewById(R.id.initial)
        nameView = findViewById(R.id.callName)
        numberView = findViewById(R.id.callNumber)
        statusView = findViewById(R.id.callStatus)
        extraView = findViewById(R.id.callExtra)
        incomingPanel = findViewById(R.id.incomingPanel)
        ongoingPanel = findViewById(R.id.ongoingPanel)
        dtmfKeypad = findViewById(R.id.dtmfKeypad)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnHold = findViewById(R.id.btnHold)
        btnSwap = findViewById(R.id.btnSwap)
        btnMerge = findViewById(R.id.btnMerge)
        btnRecord = findViewById(R.id.btnRecord)

        findViewById<FloatingActionButton>(R.id.btnAnswer).setOnClickListener { answer() }
        findViewById<FloatingActionButton>(R.id.btnReject).setOnClickListener {
            CallManager.primary()?.reject(false, null)
        }
        findViewById<MaterialButton>(R.id.btnRejectWa).setOnClickListener { rejectWithWhatsApp() }
        findViewById<MaterialButton>(R.id.btnRejectSms).setOnClickListener { rejectWithSms() }
        findViewById<FloatingActionButton>(R.id.btnHangup).setOnClickListener {
            CallManager.primary()?.disconnect()
        }
        btnMute.setOnClickListener { CallManager.service?.toggleMute() }
        btnSpeaker.setOnClickListener { chooseAudioRoute() }
        btnSpeaker.setOnLongClickListener {
            CallManager.service?.toggleSpeaker()
            true
        }
        findViewById<MaterialButton>(R.id.btnAddCall).setOnClickListener {
            // פותח את החייגן; שיחה חדשה תעביר את הנוכחית להמתנה אוטומטית
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        btnMerge.setOnClickListener {
            if (!CallManager.merge()) PhoneUtils.toast(this, "אין שיחות למיזוג")
            else PhoneUtils.toast(this, "ממזג שיחות...")
        }
        btnRecord.setOnClickListener { toggleRecording() }
        btnHold.setOnClickListener {
            val call = CallManager.primary() ?: return@setOnClickListener
            if (CallManager.stateOf(call) == Call.STATE_HOLDING) call.unhold() else call.hold()
        }
        btnSwap.setOnClickListener { CallManager.swap() }
        findViewById<MaterialButton>(R.id.btnKeypad).setOnClickListener {
            dtmfKeypad.visibility = if (dtmfKeypad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        // רקע כהה שקוף לכל הכפתורים — קריא גם על תמונה
        for (id in listOf(R.id.btnKeypad, R.id.btnAddCall, R.id.btnSwap)) {
            findViewById<MaterialButton>(id).backgroundTintList = android.content.res.ColorStateList.valueOf(0x59000000)
        }
        Keypad.build(dtmfKeypad, showLetters = false, textColor = 0xFFFFFFFF.toInt(), onKey = { k -> sendDtmf(k) }, onLongKey = { false }, dark = true)

        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ImprovedDialer:proximity")
        }

        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answer()
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        Notifications.update(this)
        CallManager.addListener(listener)
        handler.post(ticker)
        render()
    }

    override fun onStop() {
        visible = false
        Notifications.update(this)
        CallManager.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        releaseProximity()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun answer() {
        val call = CallManager.calls.firstOrNull { CallManager.isRinging(it) } ?: return
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    private fun sendDtmf(k: Char) {
        val call = CallManager.primary() ?: return
        call.playDtmfTone(k)
        handler.postDelayed({ call.stopDtmfTone() }, 180)
        dtmfTyped.append(k)
        extraView.visibility = View.VISIBLE
        extraView.text = dtmfTyped.toString()
    }

    private fun render() {
        if (finishing) return
        val call = CallManager.primary()
        if (call == null) {
            releaseProximity()
            if (!keepOpen) {
                finishing = true
                statusView.text = "השיחה הסתיימה"
                incomingPanel.visibility = View.GONE
                ongoingPanel.visibility = View.GONE
                handler.postDelayed({ finishAndRemoveTask() }, 900)
            }
            return
        }
        val state = CallManager.stateOf(call)
        val conference = CallManager.isConference(call)
        val number = if (conference) "conf:" + call.children.size else CallManager.numberOf(call)
        if (conference && number != shownNumber) {
            shownNumber = number
            val names = call.children.map { ch ->
                val n = CallManager.numberOf(ch)
                ContactsRepo.lookup(this, n).name ?: n
            }
            nameView.text = "שיחת ועידה" + if (names.isNotEmpty()) " (${names.size})" else ""
            numberView.text = names.joinToString(" · ")
            Avatar.bind(photo, initial, "ועידה", null)
            applyBackgroundPhoto(null)
            showContactDetails(null)
        } else if (number != shownNumber) {
            shownNumber = number
            dtmfTyped.setLength(0)
            val info = ContactsRepo.lookup(this, number)
            val display = info.name ?: number.ifEmpty { "מספר חסוי" }
            nameView.text = display
            numberView.text = if (info.name != null) number else ""
            Avatar.bind(photo, initial, display, info.photo)
            applyBackgroundPhoto(info.photo, info.contactId)
            showContactDetails(info.contactId)
        }

        val ringing = state == Call.STATE_RINGING
        incomingPanel.visibility = if (ringing) View.VISIBLE else View.GONE
        ongoingPanel.visibility = if (ringing) View.GONE else View.VISIBLE

        val audio = CallManager.audioState
        val muted = audio?.isMuted ?: false
        val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
        val speaker = route == CallAudioState.ROUTE_SPEAKER
        btnMute.alpha = if (muted) 1f else 0.7f
        btnMute.text = if (muted) "🔇\nמושתק" else "🎤\nהשתק"
        setOn(btnMute, muted)
        val hasExternal = audio != null && (audio.supportedRouteMask and
            (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0
        btnSpeaker.text = when (route) {
            CallAudioState.ROUTE_SPEAKER -> "🔊\nרמקול"
            CallAudioState.ROUTE_BLUETOOTH -> "🚗\nבלוטות'"
            CallAudioState.ROUTE_WIRED_HEADSET -> "🎧\nאוזניות"
            else -> if (hasExternal) "📱\nטלפון ▾" else "🔈\nרמקול"
        }
        setOn(btnSpeaker, speaker || route == CallAudioState.ROUTE_BLUETOOTH)
        btnHold.text = if (state == Call.STATE_HOLDING) "▶\nהמשך" else "⏸\nהמתנה"
        setOn(btnHold, state == Call.STATE_HOLDING)
        val top = CallManager.topLevel()
        val twoCalls = top.size > 1 && CallManager.heldCall() != null && !ringing
        btnSwap.visibility = if (twoCalls) View.VISIBLE else View.GONE
        btnMerge.visibility = if (twoCalls || (!ringing && call.details?.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) == true)) View.VISIBLE else View.GONE
        btnRecord.text = if (RecordingService.recording) "⏹\nעצור הקלטה" else "⏺\nהקלט"
        setOn(btnRecord, RecordingService.recording)

        if (!ringing && dtmfTyped.isEmpty()) {
            val others = CallManager.topLevel().size - 1
            if (others > 0) {
                extraView.visibility = View.VISIBLE
                extraView.text = "שיחה נוספת בהמתנה"
            } else {
                extraView.visibility = View.GONE
            }
        } else if (ringing) {
            val active = CallManager.calls.any { CallManager.stateOf(it) == Call.STATE_ACTIVE }
            extraView.visibility = if (active) View.VISIBLE else View.GONE
            extraView.text = "שיחה ממתינה — מענה יעביר את השיחה הנוכחית להמתנה"
        }

        // חיישן קירבה: מסך כבוי כשהטלפון צמוד לאוזן
        if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING) {
            if (speaker) releaseProximity() else acquireProximity()
        } else {
            releaseProximity()
        }

        if (state == Call.STATE_SELECT_PHONE_ACCOUNT && !accountDialogShown) showAccountPicker(call)
        maybeScheduleAutoBack(call, state)
        updateStatus()
    }

    private fun updateStatus() {
        if (finishing) return
        val call = CallManager.primary() ?: return
        statusView.text = when (CallManager.stateOf(call)) {
            Call.STATE_RINGING -> "שיחה נכנסת"
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "מחייג..."
            Call.STATE_SELECT_PHONE_ACCOUNT -> "בחר כרטיס SIM"
            Call.STATE_HOLDING -> "בהמתנה"
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "השיחה הסתיימה"
            Call.STATE_ACTIVE -> {
                val start = call.details?.connectTimeMillis ?: 0L
                val t = if (start > 0) formatDuration((System.currentTimeMillis() - start) / 1000) else "מחובר"
                if (RecordingService.recording) "⏺ $t" else t
            }
            else -> ""
        }
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun acquireProximity() {
        val l = proximityLock ?: return
        if (!l.isHeld) l.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseProximity() {
        val l = proximityLock ?: return
        if (l.isHeld) l.release()
    }

    // ---------- נכתב: 2026-11-05 19:10 — תמונת איש הקשר על כל המסך ----------
    private var hasBgPhoto = false

    private fun applyBackgroundPhoto(photoUri: String?, contactId: Long? = null) {
        val bg = findViewById<ImageView>(R.id.bgPhoto)
        val scrim = findViewById<View>(R.id.bgScrim)
        val avatarBox = findViewById<View>(R.id.avatarBox)
        var ok = false
        // עודכן: 2026-11-06 15:45 — קודם כרטיס הפרופיל (על כל המסך), אחרת התמונה הרגילה לכל הרוחב
        val card = contactId?.let { ProfileCard.find(this, it, resources.displayMetrics.widthPixels) }
        val lp = bg.layoutParams as android.widget.FrameLayout.LayoutParams
        if (card != null) {
            bg.setImageBitmap(card)
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            bg.scaleType = ImageView.ScaleType.CENTER_CROP
            bg.adjustViewBounds = false
            bg.layoutParams = lp
            ok = true
        } else {
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            bg.scaleType = ImageView.ScaleType.FIT_CENTER
            bg.adjustViewBounds = true
            bg.layoutParams = lp
        }
        if (!ok && !photoUri.isNullOrEmpty()) {
            try {
                bg.setImageURI(null)
                bg.setImageURI(android.net.Uri.parse(photoUri))
                ok = bg.drawable != null
            } catch (e: Exception) {
                ok = false
            }
        }
        hasBgPhoto = ok
        bg.visibility = if (ok) View.VISIBLE else View.GONE
        scrim.visibility = View.GONE // עודכן: 2026-11-05 19:20 — בלי הצללה
        // עם תמונה גדולה — בלי העיגול הקטן
        avatarBox.visibility = if (ok) View.GONE else View.VISIBLE
        window.statusBarColor = if (ok) 0xFF000000.toInt() else androidx.core.content.ContextCompat.getColor(this, R.color.call_bg)
        // צל לטקסט כדי שיהיה קריא על כל תמונה
        for (t in listOf(nameView, numberView, statusView, extraView)) {
            if (ok) t.setShadowLayer(10f, 0f, 2f, 0xE6000000.toInt()) else t.setShadowLayer(0f, 0f, 0f, 0)
        }
        nameView.textSize = if (ok) 34f else 30f
        (nameView.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
            it.topMargin = ((if (ok) 48 else 18) * resources.displayMetrics.density).toInt()
            nameView.layoutParams = it
        }
    }

    // ---------- נכתב: 2026-11-05 17:55 — פרטים נוספים מאנשי הקשר ----------
    /** עודכן: 2026-11-05 19:20 — רק ההערה מאנשי הקשר, כבאנר רץ מעל הכפתורים */
    private fun showContactDetails(contactId: Long?) {
        val tv = findViewById<TextView>(R.id.callInfo)
        val note = contactId?.let { ContactsRepo.details(this, it) }?.note
            ?.replace(Regex("\\s*\\n\\s*"), "  •  ")?.trim()
        if (note.isNullOrBlank()) {
            tv.visibility = View.GONE
            return
        }
        tv.text = "📝 $note"
        tv.visibility = View.VISIBLE
        tv.isSelected = true // מפעיל את הגלילה (marquee)
    }

    // ---------- נכתב: 2026-11-01 23:00 — שמע, הקלטה ----------

    /** כפתור "פעיל" מודגש */
    private fun setOn(b: MaterialButton, on: Boolean) {
        // רקע כהה שקוף — קריא גם על תמונת רקע
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) 0x991FA463.toInt() else 0x59000000)
        b.alpha = if (on) 1f else 0.85f
    }

    /** בחירת יעד שמע: טלפון / רמקול / דיבורית בלוטות' / אוזניות */
    @Suppress("DEPRECATION")
    private fun chooseAudioRoute() {
        val svc = CallManager.service ?: return
        val audio = CallManager.audioState
        if (audio == null) {
            svc.toggleSpeaker()
            return
        }
        val mask = audio.supportedRouteMask
        val hasExternal = (mask and (CallAudioState.ROUTE_BLUETOOTH or CallAudioState.ROUTE_WIRED_HEADSET)) != 0
        if (!hasExternal) {
            svc.toggleSpeaker() // אין דיבורית — לחיצה מחליפה בין טלפון לרמקול
            return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) {
            labels.add("📱 טלפון (אפרכסת)")
            actions.add { svc.routeTo(CallAudioState.ROUTE_EARPIECE) }
        }
        labels.add("🔊 רמקול")
        actions.add { svc.routeTo(CallAudioState.ROUTE_SPEAKER) }
        if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            labels.add("🎧 אוזניות")
            actions.add { svc.routeTo(CallAudioState.ROUTE_WIRED_HEADSET) }
        }
        if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) {
            val devices = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                audio.supportedBluetoothDevices.toList() else emptyList()
            if (devices.isEmpty()) {
                labels.add("🚗 דיבורית בלוטות'")
                actions.add { svc.routeToBluetooth(null) }
            } else {
                for (d in devices) {
                    val name = try {
                        d.name
                    } catch (e: SecurityException) {
                        null
                    } ?: "בלוטות'"
                    labels.add("🚗 $name")
                    actions.add { svc.routeToBluetooth(d) }
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("לאן להעביר את השמע?")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private val micPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) toggleRecording() else PhoneUtils.toast(this, "הקלטה צריכה הרשאת מיקרופון") }

    private var helperAsked = false

    /** הסבר והפעלה של "עזר הקלטה" (שירות נגישות) — כך אפליקציות הקלטה אחרות מקליטות שיחות */
    private fun showRecordHelperDialog(firstTime: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (firstTime) "הפעלת הקלטת שיחות" else "ההקלטה נחסמה")
            .setMessage(
                "אנדרואיד חוסם הקלטה בזמן שיחה, חוץ מאפליקציות עם שירות נגישות פעיל — כך עובדות אפליקציות ההקלטה האחרות.\n\n" +
                    "במסך שייפתח: אפליקציות מותקנות ← \"חייגן משופר – עזר הקלטת שיחות\" ← הפעל.\n" +
                    "(אם האפשרות אפורה — קודם \"התר הגדרות מוגבלות\" בפרטי האפליקציה.)\n\nהשירות לא קורא את המסך ולא אוסף מידע."
            )
            .setPositiveButton("להפעלה") { _, _ ->
                try {
                    startActivity(RecordHelperService.settingsIntent())
                } catch (e: Exception) {
                }
            }
            .setNegativeButton(if (firstTime) "הקלט בכל זאת" else "סגור") { _, _ -> if (firstTime) toggleRecording() }
            .show()
    }

    private fun toggleRecording() {
        if (RecordingService.recording) {
            RecordingService.stop(this)
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        // עודכן: 2026-11-08 12:10 — בלי "עזר הקלטה" בנגישות אנדרואיד חוסם הקלטה בזמן שיחה
        if (!RecordHelperService.isEnabled(this) && !helperAsked) {
            helperAsked = true
            showRecordHelperDialog(firstTime = true)
            return
        }
        RecordingService.onSilenceDetected = {
            runOnUiThread {
                if (!RecordHelperService.isEnabled(this)) {
                    showRecordHelperDialog(firstTime = false)
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("ההקלטה שקטה")
                        .setMessage("ניסיתי את כל מקורות השמע והטלפון מחזיר שקט. נסה להפעיל רמקול ולהקליט שוב.")
                        .setPositiveButton("הבנתי", null)
                        .show()
                }
            }
        }
        RecordingService.start(this, nameView.text.toString())
        val speakerOn = CallManager.audioState?.route == CallAudioState.ROUTE_SPEAKER
        if (!speakerOn) PhoneUtils.toastLong(this, "מקליט ⏺ — כדי שהצד השני יוקלט בבירור, מומלץ להפעיל רמקול")
    }

    // ---------- דחייה עם הודעה ----------

    private fun rejectWithWhatsApp() {
        val call = CallManager.calls.firstOrNull { CallManager.isRinging(it) } ?: return
        val number = CallManager.numberOf(call)
        if (number.isBlank()) {
            PhoneUtils.toast(this, "מספר חסוי — אי אפשר לשלוח וואטסאפ")
            return
        }
        val msgs = Prefs.quickMessages(this)
        val items = (msgs + "✏ בלי הודעה — רק לפתוח צ'אט").toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("דחייה והודעה בוואטסאפ")
            .setItems(items) { _, which ->
                val text = if (which < msgs.size) msgs[which] else null
                keepOpen = true
                call.reject(false, null)
                openWhatsAppUnlocked(number, text)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun openWhatsAppUnlocked(number: String, text: String?) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            PhoneUtils.toast(this, "פתח את הנעילה כדי לשלוח בוואטסאפ")
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    PhoneUtils.openWhatsApp(this@CallActivity, number, text)
                    closeNow()
                }

                override fun onDismissCancelled() {
                    closeNow()
                }

                override fun onDismissError() {
                    closeNow()
                }
            })
        } else {
            PhoneUtils.openWhatsApp(this, number, text)
            closeNow()
        }
    }

    private fun rejectWithSms() {
        val call = CallManager.calls.firstOrNull { CallManager.isRinging(it) } ?: return
        if (CallManager.numberOf(call).isBlank()) {
            PhoneUtils.toast(this, "מספר חסוי — אי אפשר לשלוח SMS")
            return
        }
        val msgs = Prefs.quickMessages(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("דחייה והודעת SMS")
            .setItems(msgs.toTypedArray()) { _, which -> call.reject(true, msgs[which]) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun closeNow() {
        keepOpen = false
        if (CallManager.primary() == null) {
            finishing = true
            finishAndRemoveTask()
        } else {
            render()
        }
    }

    // ---------- בחירת SIM (כשהטלפון מוגדר "שאל בכל פעם") ----------

    private fun showAccountPicker(call: Call) {
        accountDialogShown = true
        val tm = getSystemService(TelecomManager::class.java)
        val handles: List<PhoneAccountHandle> = try {
            tm?.callCapablePhoneAccounts ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (handles.isEmpty()) {
            PhoneUtils.toast(this, "לא נמצא כרטיס SIM זמין")
            call.disconnect()
            return
        }
        if (handles.size == 1) {
            call.phoneAccountSelected(handles[0], false)
            return
        }
        val labels = handles.map { h ->
            tm?.getPhoneAccount(h)?.label?.toString() ?: h.id
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("לחייג דרך איזה SIM?")
            .setItems(labels) { _, which -> call.phoneAccountSelected(handles[which], false) }
            .setOnCancelListener { call.disconnect() }
            .show()
    }
}
