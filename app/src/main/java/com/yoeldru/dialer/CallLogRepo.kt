// נכתב: 2026-11-01 10:55 (שעון ישראל) — חייגן משופר: יומן שיחות אחרונות
package com.yoeldru.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

class RecentCall(
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val photo: String?,
    val contactId: Long?,
    var count: Int,
    val durationSec: Long = 0L // נכתב: 2026-11-02 22:40 — משך השיחה (של האחרונה בקבוצה)
)

object CallLogRepo {
    fun load(ctx: Context, phones: List<PhoneEntry>, limit: Int = 300): List<RecentCall> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val byLocal = HashMap<String, PhoneEntry>()
        for (p in phones) if (p.local.isNotEmpty()) byLocal.putIfAbsent(p.local, p)

        val out = ArrayList<RecentCall>()
        val proj = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.DURATION
        )
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows < limit) {
                    rows++
                    val number = c.getString(0) ?: ""
                    val type = c.getInt(2)
                    val date = c.getLong(3)
                    val last = out.lastOrNull()
                    // קיבוץ שיחות רצופות מאותו מספר ובאותו סוג
                    if (last != null && last.number == number && last.type == type) {
                        last.count++
                        continue
                    }
                    val entry = byLocal[PhoneUtils.localDigits(number)]
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: entry?.name
                        ?: NameVoiceStore.label(ctx, number)?.let { "🏷 $it" } // נכתב: 2026-11-08 10:50
                    val photo = c.getString(4) ?: entry?.photo
                    out.add(RecentCall(number, name, type, date, photo, entry?.contactId, 1, c.getLong(5)))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
