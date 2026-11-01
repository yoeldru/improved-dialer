// נכתב: 2026-11-01 12:20 (שעון ישראל) — חייגן משופר: בדיקת עדכונים, התראה, הורדה והתקנה מתוך האפליקציה, ושיתוף
package com.yoeldru.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

object Updater {
    private const val REPO = "yoeldru/improved-dialer"
    const val LATEST_APK = "https://github.com/$REPO/releases/latest/download/ImprovedDialer.apk"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    private const val PREFS = "settings"
    private const val K_CODE = "upd_code"
    private const val K_NAME = "upd_name"
    private const val K_URL = "upd_url"
    private const val K_NOTES = "upd_notes"
    private const val K_NOTIFIED = "upd_notified"

    private const val CHANNEL = "updates_v1"
    private const val NOTIF_ID = 2001
    const val EXTRA_SHOW_UPDATE = "show_update"

    fun currentVersionCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** פונה לגיטהאב (רשת — לא להריץ על ה-UI) */
    fun fetchLatest(): UpdateInfo? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ImprovedDialer")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name", "")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return null
            var apk = LATEST_APK
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url", apk)
                        break
                    }
                }
            }
            UpdateInfo(code, tag.removePrefix("v"), apk, json.optString("body", ""))
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** בודק ושומר אם יש עדכון. מחזיר את העדכון אם קיים. */
    fun check(ctx: Context): UpdateInfo? {
        val latest = fetchLatest() ?: return pending(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (latest.versionCode > currentVersionCode(ctx)) {
            sp.edit()
                .putLong(K_CODE, latest.versionCode)
                .putString(K_NAME, latest.versionName)
                .putString(K_URL, latest.apkUrl)
                .putString(K_NOTES, latest.notes)
                .apply()
            latest
        } else {
            sp.edit().remove(K_CODE).apply()
            null
        }
    }

    /** עדכון שנמצא בבדיקה קודמת (בלי רשת) */
    fun pending(ctx: Context): UpdateInfo? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = sp.getLong(K_CODE, 0L)
        if (code <= currentVersionCode(ctx)) return null
        return UpdateInfo(
            code,
            sp.getString(K_NAME, "") ?: "",
            sp.getString(K_URL, LATEST_APK) ?: LATEST_APK,
            sp.getString(K_NOTES, "") ?: ""
        )
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(8, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update_check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** התראה בטלפון — פעם אחת לכל גרסה */
    fun notifyIfNew(ctx: Context, info: UpdateInfo) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getLong(K_NOTIFIED, 0L) >= info.versionCode) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "עדכוני אפליקציה", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SHOW_UPDATE, true)
        val pi = PendingIntent.getActivity(ctx, 10, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_call)
            .setContentTitle("עדכון זמין לחייגן משופר")
            .setContentText("גרסה ${info.versionName} מוכנה — הקש כדי לעדכן")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIF_ID, n)
            sp.edit().putLong(K_NOTIFIED, info.versionCode).apply()
        } catch (e: SecurityException) {
        }
    }

    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    // ---------- ממשק: הודעה קופצת, הורדה והתקנה ----------

    fun prompt(activity: AppCompatActivity, info: UpdateInfo) {
        val msg = StringBuilder("גרסה ${info.versionName} זמינה (מותקנת: ${currentVersionName(activity)}).")
        if (info.notes.isNotBlank()) msg.append("\n\n").append(info.notes.take(600))
        MaterialAlertDialogBuilder(activity)
            .setTitle("🔴 עדכון זמין")
            .setMessage(msg.toString())
            .setPositiveButton("עדכן עכשיו") { _, _ -> install(activity, info) }
            .setNegativeButton("אחר כך", null)
            .show()
    }

    fun install(activity: AppCompatActivity, info: UpdateInfo) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("נדרש אישור חד-פעמי")
                .setMessage("כדי לעדכן מתוך האפליקציה צריך לאשר לה \"התקנת אפליקציות לא מוכרות\". במסך שייפתח הפעל את האפשרות, חזור ולחץ שוב על עדכון.")
                .setPositiveButton("לאישור") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                        )
                    } catch (e: Exception) {
                        PhoneUtils.toast(activity, "פתח הגדרות ← התקנת אפליקציות לא מוכרות")
                    }
                }
                .setNegativeButton("ביטול", null)
                .show()
            return
        }

        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = true }
        val label = TextView(activity).apply { text = "מוריד..." }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
            addView(label)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("מוריד עדכון")
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()

        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                download(activity, info.apkUrl) { pct ->
                    activity.runOnUiThread {
                        bar.isIndeterminate = false
                        bar.progress = pct
                        label.text = "מוריד... $pct%"
                    }
                }
            }
            dialog.dismiss()
            if (file == null) {
                PhoneUtils.toast(activity, "ההורדה נכשלה — בדוק חיבור לאינטרנט")
                return@launch
            }
            cancelNotification(activity)
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                PhoneUtils.toast(activity, "לא ניתן לפתוח את ההתקנה")
            }
        }
    }

    private fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        val out = File(dir, "ImprovedDialer.apk")
        var conn: HttpURLConnection? = null
        return try {
            var current = url
            var redirects = 0
            while (true) {
                conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "ImprovedDialer")
                val code = conn.responseCode
                if (code in 300..399 && redirects < 6) {
                    current = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code != 200) return null
                break
            }
            val c = conn ?: return null
            val total = c.contentLengthLong
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---------- שיתוף ----------

    fun share(ctx: Context) {
        val text = "📞 חייגן משופר — חייגן חכם בעברית: חיפוש T9, חיוג קולי, מועדפים עם תמונות, " +
            "ודחיית שיחה עם הודעת וואטסאפ.\n\nלהורדה והתקנה (אנדרואיד):\n$LATEST_APK"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "חייגן משופר")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, "שתף את חייגן משופר").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val info = withContext(Dispatchers.IO) { Updater.check(applicationContext) }
        if (info != null) Updater.notifyIfNew(applicationContext, info)
        return Result.success()
    }
}
