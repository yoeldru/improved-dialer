// נכתב: 2026-11-01 20:15 (שעון ישראל) — חייגן משופר: הסבר כשאנדרואיד חוסם הגדרה כחייגן ברירת מחדל ("הגדרות מוגבלות")
package com.yoeldru.dialer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DefaultDialerHelp {

    fun show(activity: AppCompatActivity) {
        val msg = """
אנדרואיד חוסם אפליקציות שהותקנו מקובץ (לא מחנות Play) מלהיות חייגן ברירת מחדל, עד שמאשרים זאת ידנית פעם אחת.

1. לחץ "פתח הגדרות" למטה (פרטי האפליקציה).
2. לחץ על ⋮ (שלוש נקודות) בפינה העליונה.
3. בחר "התר הגדרות מוגבלות" / "Allow restricted settings" ואשר (קוד/טביעת אצבע).
4. חזור לאפליקציה ← ⋮ הגדרות ← "הגדר כחייגן ברירת מחדל".

אם אין שלוש נקודות במסך — נסה שוב להגדיר כברירת מחדל ואז חזור למסך הזה; האפשרות מופיעה רק אחרי ניסיון שנחסם.
        """.trimIndent()
        MaterialAlertDialogBuilder(activity)
            .setTitle("צריך לאשר \"הגדרות מוגבלות\"")
            .setMessage(msg)
            .setPositiveButton("פתח הגדרות") { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                    )
                } catch (e: Exception) {
                    PhoneUtils.toast(activity, "פתח הגדרות ← אפליקציות ← חייגן משופר")
                }
            }
            .setNegativeButton("אחר כך", null)
            .show()
    }
}
