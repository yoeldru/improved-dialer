// נכתב: 2026-11-08 12:00 (שעון ישראל) — חייגן משופר: שירות נגישות ריק שמאפשר הקלטה בזמן שיחה
// (אנדרואיד מתיר הקלטה בזמן שיחה לאפליקציות עם שירות נגישות פעיל — כך עובדות אפליקציות הקלטה אחרות)
package com.yoeldru.dialer

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

class RecordHelperService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, RecordHelperService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) || it.endsWith("/.RecordHelperService") && it.startsWith(ctx.packageName) }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
