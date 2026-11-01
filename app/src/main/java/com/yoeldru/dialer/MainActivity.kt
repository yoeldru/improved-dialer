// נכתב: 2026-11-01 11:10 (שעון ישראל) — חייגן משופר: מסך ראשי (חייגן ↔ מועדפים בהחלקה), הרשאות, חיוג קולי
package com.yoeldru.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        /** מספר שהגיע מאפליקציה אחרת (קישור tel:) וממתין להצגה בחייגן */
        @JvmStatic
        var pendingDial: String? = null
    }

    private lateinit var pager: ViewPager2
    private var roleAsked = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestRoleIfNeeded()
    }

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!list.isNullOrEmpty()) handleVoice(list)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment =
                if (position == 0) DialerFragment() else FavoritesFragment()
        }
        TabLayoutMediator(findViewById<TabLayout>(R.id.tabs), pager) { tab, pos ->
            tab.text = if (pos == 0) "☎ חייגן" else "⭐ מועדפים"
        }.attach()

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { Updater.share(this) }
        findViewById<View>(R.id.updateChip).setOnClickListener {
            Updater.pending(this)?.let { Updater.prompt(this, it) }
        }

        handleIntent(intent)
        requestPermissionsIfNeeded()
        Updater.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateBadge()
        checkForUpdate()
    }

    // ---------- עדכונים ----------

    private var promptedThisSession = 0L

    private fun refreshUpdateBadge() {
        val has = Updater.pending(this) != null
        findViewById<View>(R.id.updateDot).visibility = if (has) View.VISIBLE else View.GONE
        findViewById<View>(R.id.updateChip).visibility = if (has) View.VISIBLE else View.GONE
    }

    private fun checkForUpdate() {
        val fromNotification = intent?.getBooleanExtra(Updater.EXTRA_SHOW_UPDATE, false) == true
        if (fromNotification) intent?.removeExtra(Updater.EXTRA_SHOW_UPDATE)
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Updater.check(applicationContext) }
            refreshUpdateBadge()
            if (info == null) return@launch
            Updater.notifyIfNew(applicationContext, info)
            // הודעה קופצת בתוך האפליקציה — פעם אחת לכל גרסה בכל פתיחה
            if (fromNotification || promptedThisSession < info.versionCode) {
                promptedThisSession = info.versionCode
                if (!isFinishing) Updater.prompt(this@MainActivity, info)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel") {
            pendingDial = PhoneUtils.cleanNumber(data.schemeSpecificPart ?: "")
            if (::pager.isInitialized) pager.currentItem = 0
            // אם החייגן כבר פתוח — לעדכן מיד
            val f = supportFragmentManager.findFragmentByTag("f0") as? DialerFragment
            if (f != null && f.isResumed) {
                pendingDial?.let { f.setTyped(it) }
                pendingDial = null
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestRoleIfNeeded() else permLauncher.launch(missing.toTypedArray())
    }

    fun requestRoleIfNeeded(force: Boolean = false) {
        if (roleAsked && !force) return
        roleAsked = true
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    // ---------- חיוג קולי ----------

    fun startVoiceDial() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "למי לחייג? למשל: \"חייג לאושרה ראש\"")
        }
        try {
            voiceLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            PhoneUtils.toast(this, "זיהוי דיבור לא זמין במכשיר")
        }
    }

    private fun handleVoice(spoken: List<String>) {
        lifecycleScope.launch {
            val phones = withContext(Dispatchers.IO) {
                if (ContactsRepo.phones.isEmpty()) ContactsRepo.load(applicationContext) else ContactsRepo.phones
            }
            val candidates = VoiceMatcher.match(spoken, phones)
            when {
                candidates.size == 1 -> {
                    val p = candidates[0]
                    PhoneUtils.toast(this@MainActivity, "מחייג ל" + p.name)
                    PhoneUtils.placeCall(this@MainActivity, p.number)
                }
                candidates.size > 1 -> {
                    Dialogs.showCandidates(this@MainActivity, "למי לחייג?", candidates) { p ->
                        PhoneUtils.placeCall(this@MainActivity, p.number)
                    }
                }
                else -> {
                    val num = VoiceMatcher.extractNumber(spoken)
                    if (num.length >= 9) {
                        PhoneUtils.toast(this@MainActivity, "מחייג ל-$num")
                        PhoneUtils.placeCall(this@MainActivity, num)
                    } else {
                        PhoneUtils.toast(this@MainActivity, "לא נמצא איש קשר מתאים ל: \"" + spoken[0] + "\"")
                    }
                }
            }
        }
    }
}
