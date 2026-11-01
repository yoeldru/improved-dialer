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
        updateStatus()
        refreshUpdateButton()
    }

    private fun refreshUpdateButton() {
        val info = Updater.pending(this)
        val btn = findViewById<MaterialButton>(R.id.btnUpdate)
        btn.visibility = if (info != null) android.view.View.VISIBLE else android.view.View.GONE
        if (info != null) btn.text = "🔴 עדכון לגרסה " + info.versionName + " — עדכן עכשיו"
    }

    private fun updateStatus() {
        status.text = if (PhoneUtils.isDefaultDialer(this)) "✔ האפליקציה מוגדרת כחייגן ברירת המחדל"
        else "✖ האפליקציה עדיין לא חייגן ברירת המחדל — שיחות נכנסות, דחייה עם הודעה וחסימה דורשים זאת"
    }
}
