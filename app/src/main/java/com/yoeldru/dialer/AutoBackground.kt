// נכתב: 2026-11-02 20:30 (שעון ישראל) — חייגן משופר: מסך השיחה עובר לרקע אחרי כמה שניות כשוויז / אפליקציית ניווט פתוחה
package com.yoeldru.dialer

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process

object AutoBackground {
    const val MODE_OFF = 0
    const val MODE_NAV_ONLY = 1
    const val MODE_ALWAYS = 2

    private const val FILE = "settings"
    private const val K_MODE = "autobg_mode"
    private const val K_SECONDS = "autobg_seconds"
    private const val K_APPS = "autobg_apps"

    /** אפליקציות ניווט ידועות — ברירת מחדל */
    val defaultApps = setOf(
        "com.waze",
        "com.google.android.apps.maps",
        "com.moovit.android",
        "com.tranzmate",
        "com.sygic.aura",
        "com.here.app.maps",
        "com.google.android.projection.gearhead" // Android Auto
    )

    /** החבילה שהייתה מוצגת רגע לפני שהשיחה התחילה */
    @Volatile
    var previousApp: String? = null

    fun mode(ctx: Context): Int = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(K_MODE, MODE_NAV_ONLY)
    fun setMode(ctx: Context, m: Int) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(K_MODE, m).apply()

    fun seconds(ctx: Context): Int = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(K_SECONDS, 4)
    fun setSeconds(ctx: Context, s: Int) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(K_SECONDS, s).apply()

    fun apps(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(K_APPS, null)?.toSet() ?: defaultApps

    fun setApps(ctx: Context, set: Set<String>) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet(K_APPS, set).apply()

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** מוצא את האפליקציה האחרונה שהייתה בחזית (לא אנחנו) */
    fun detectPreviousApp(ctx: Context): String? {
        if (!hasUsageAccess(ctx)) return null
        return try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10 * 60 * 1000L, now)
            val e = UsageEvents.Event()
            // עודכן: 2026-11-03 14:45 — האפליקציה האחרונה שעלתה למסך (כולל אנחנו), כדי לא "לחזור" לוויז ישן
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
            }
            last
        } catch (e: Exception) {
            null
        }
    }

    /** האם להעביר את מסך השיחה לרקע */
    fun shouldGoBack(ctx: Context): Boolean {
        if (previousApp == ctx.packageName) return false // החיוג היה מתוך האפליקציה
        return modeDecision(ctx)
    }

    private fun modeDecision(ctx: Context): Boolean = when (mode(ctx)) {
        MODE_ALWAYS -> previousApp != null || !hasUsageAccess(ctx)
        MODE_NAV_ONLY -> previousApp?.let { it in apps(ctx) } == true
        else -> false
    }
}
