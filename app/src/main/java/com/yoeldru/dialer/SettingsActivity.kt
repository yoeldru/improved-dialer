// נכתב: 2026-11-01 11:15 (שעון ישראל) — חייגן משופר: מסך הגדרות
package com.yoeldru.dialer

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateStatus()
        if (!PhoneUtils.isDefaultDialer(this)) DefaultDialerHelp.show(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.defaultStatus)

        val edit = findViewById<EditText>(R.id.editMessages)
        edit.setText(Prefs.quickMessages(this).joinToString("\n"))

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            Prefs.setQuickMessages(this, edit.text.toString())
            PhoneUtils.toast(this, "נשמר")
        }

        findViewById<MaterialButton>(R.id.btnDefault).setOnClickListener {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (e: Exception) {
                    PhoneUtils.toast(this, "פתח הגדרות ← אפליקציות ברירת מחדל")
                }
            }
        }

        // נכתב: 2026-11-02 20:40 — מעבר לרקע בזמן ניווט
        val modeGroup = findViewById<android.widget.RadioGroup>(R.id.autoBgMode)
        modeGroup.check(
            when (AutoBackground.mode(this)) {
                AutoBackground.MODE_ALWAYS -> R.id.autoBgAlways
                AutoBackground.MODE_OFF -> R.id.autoBgOff
                else -> R.id.autoBgNav
            }
        )
        modeGroup.setOnCheckedChangeListener { _, id ->
            val m = when (id) {
                R.id.autoBgAlways -> AutoBackground.MODE_ALWAYS
                R.id.autoBgOff -> AutoBackground.MODE_OFF
                else -> AutoBackground.MODE_NAV_ONLY
            }
            AutoBackground.setMode(this, m)
            if (m == AutoBackground.MODE_NAV_ONLY && !AutoBackground.hasUsageAccess(this)) askUsageAccess()
            refreshAutoBg()
        }
        val secLabel = findViewById<TextView>(R.id.autoBgSecondsLabel)
        val slider = findViewById<com.google.android.material.slider.Slider>(R.id.autoBgSeconds)
        slider.value = AutoBackground.seconds(this).coerceIn(2, 15).toFloat()
        secLabel.text = "מעבר לרקע אחרי ${slider.value.toInt()} שניות"
        slider.addOnChangeListener { _, v, _ ->
            AutoBackground.setSeconds(this, v.toInt())
            secLabel.text = "מעבר לרקע אחרי ${v.toInt()} שניות"
        }
        findViewById<MaterialButton>(R.id.btnNavApps).setOnClickListener { pickNavApps() }
        findViewById<MaterialButton>(R.id.btnUsageAccess).setOnClickListener {
            try {
                startActivity(AutoBackground.usageAccessIntent())
            } catch (e: Exception) {
                PhoneUtils.toast(this, "פתח הגדרות ← גישה לנתוני שימוש")
            }
        }

        // נכתב: 2026-11-02 20:25 — מצב נהיגה: מילת הפעלה, פקודה ישירה, בדיקת שמיעה
        val wakeEdit = findViewById<EditText>(R.id.editWake)
        wakeEdit.setText(Prefs.wakeWord(this))
        val direct = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDirect)
        direct.isChecked = Prefs.directCommands(this)
        direct.setOnCheckedChangeListener { _, on -> Prefs.setDirectCommands(this, on); restartDrivingIfRunning() }
        findViewById<MaterialButton>(R.id.btnSaveWake).setOnClickListener {
            val w = wakeEdit.text.toString().trim()
            if (w.isEmpty() || w.split(Regex("\\s+")).size > 2) {
                PhoneUtils.toast(this, "מילה אחת או שתיים")
            } else {
                Prefs.setWakeWord(this, w)
                PhoneUtils.toast(this, "נשמר: \"$w\"")
                restartDrivingIfRunning()
            }
        }
        findViewById<MaterialButton>(R.id.btnTestVoice).setOnClickListener {
            try {
                testLauncher.launch(SpeechHelper.activityIntent(this, "אמור משהו — למשל שם של איש קשר"))
            } catch (e: Exception) {
                PhoneUtils.toast(this, "זיהוי דיבור לא זמין")
            }
        }

        findViewById<MaterialButton>(R.id.btnRecordings).setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnBlocked).setOnClickListener {
            try {
                val blockedIntent = getSystemService(TelecomManager::class.java)?.createManageBlockedNumbersIntent()
                if (blockedIntent != null) startActivity(blockedIntent)
            } catch (e: Exception) {
                PhoneUtils.toast(this, "לא ניתן לפתוח את רשימת החסומים")
            }
        }

        findViewById<MaterialButton>(R.id.btnAppSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        findViewById<TextView>(R.id.versionText).text = "חייגן משופר · גרסה " + Updater.currentVersionName(this)

        // נכתב: 2026-11-01 12:25 — עדכונים ושיתוף
        findViewById<MaterialButton>(R.id.btnUpdate).setOnClickListener {
            Updater.pending(this)?.let { Updater.install(this, it) }
        }
        findViewById<MaterialButton>(R.id.btnCheckUpdate).setOnClickListener {
            PhoneUtils.toast(this, "בודק...")
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { Updater.check(applicationContext) }
                refreshUpdateButton()
                if (info != null) Updater.prompt(this@SettingsActivity, info)
                else PhoneUtils.toast(this@SettingsActivity, "יש לך את הגרסה האחרונה ✔")
            }
        }
        findViewById<MaterialButton>(R.id.btnShareApp).setOnClickListener { Updater.share(this) }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAutoBg()
        updateStatus()
        refreshUpdateButton()
    }

    private fun refreshUpdateButton() {
        val info = Updater.pending(this)
        val btn = findViewById<MaterialButton>(R.id.btnUpdate)
        btn.visibility = if (info != null) android.view.View.VISIBLE else android.view.View.GONE
        if (info != null) btn.text = "🔴 עדכון לגרסה " + info.versionName + " — עדכן עכשיו"
    }

    private val testLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val list = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS) ?: return@registerForActivityResult
        val matches = VoiceMatcher.match(list, ContactsRepo.phones).map { it.name }.distinct()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🎤 תוצאת הבדיקה")
            .setMessage(
                "הטלפון שמע:\n" + list.joinToString("\n") { "• $it" } +
                    "\n\nאנשי קשר מתאימים:\n" + (if (matches.isEmpty()) "— לא נמצא —" else matches.take(5).joinToString("\n") { "• $it" })
            )
            .setPositiveButton("סגור", null)
            .show()
    }

    private fun refreshAutoBg() {
        val b = findViewById<MaterialButton>(R.id.btnUsageAccess)
        val ok = AutoBackground.hasUsageAccess(this)
        b.text = if (ok) "✔ יש גישה לזיהוי האפליקציה הפתוחה" else "⚠ אשר \"גישה לנתוני שימוש\" (נדרש לזיהוי וויז)"
        b.visibility = if (AutoBackground.mode(this) == AutoBackground.MODE_OFF) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun askUsageAccess() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("זיהוי וויז")
            .setMessage("כדי לדעת שוויז פתוח, האפליקציה צריכה \"גישה לנתוני שימוש\".\n\nבמסך שייפתח: בחר \"חייגן משופר\" והפעל.\n(אם האפשרות אפורה — קודם \"התר הגדרות מוגבלות\" כמו בהגדרת חייגן ברירת מחדל.)")
            .setPositiveButton("לאישור") { _, _ ->
                try {
                    startActivity(AutoBackground.usageAccessIntent())
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("אחר כך", null)
            .show()
    }

    /** בחירת אפליקציות: מתוך האפליקציות המותקנות, אפליקציות ניווט מסומנות מראש */
    private fun pickNavApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
        val selected = AutoBackground.apps(this).toMutableSet()
        // אפליקציות מסומנות קודם, אחר כך לפי שם
        val sorted = apps.sortedWith(compareBy<Pair<String, String>> { if (it.first in selected) 0 else 1 }.thenBy { it.second })
        val labels = sorted.map { it.second }.toTypedArray()
        val checked = sorted.map { it.first in selected }.toBooleanArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("אפליקציות שבזמן שהן פתוחות — מסך השיחה יעבור לרקע")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected.add(sorted[which].first) else selected.remove(sorted[which].first)
            }
            .setPositiveButton("שמור") { _, _ ->
                AutoBackground.setApps(this, selected)
                PhoneUtils.toast(this, "נשמר (${selected.count { s -> apps.any { it.first == s } }} אפליקציות)")
            }
            .setNeutralButton("ברירת מחדל") { _, _ -> AutoBackground.setApps(this, AutoBackground.defaultApps) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun restartDrivingIfRunning() {
        if (DrivingModeService.running) {
            DrivingModeService.stop(this)
            DrivingModeService.start(this)
        }
    }

    private fun updateStatus() {
        status.text = if (PhoneUtils.isDefaultDialer(this)) "✔ האפליקציה מוגדרת כחייגן ברירת המחדל"
        else "✖ האפליקציה עדיין לא חייגן ברירת המחדל — שיחות נכנסות, דחייה עם הודעה וחסימה דורשים זאת"
    }
}
