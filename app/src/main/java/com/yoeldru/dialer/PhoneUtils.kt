// נכתב: 2026-11-01 10:55 (שעון ישראל) — חייגן משופר: כלי עזר לחיוג, וואטסאפ, SMS וחסימה
package com.yoeldru.dialer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.content.ContextCompat

object PhoneUtils {

    /** ספרות בלבד (ו-+ בהתחלה אם יש) */
    fun cleanNumber(s: String): String {
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (ch.isDigit() || ch == '*' || ch == '#') sb.append(ch)
            else if (ch == '+' && i == 0) sb.append(ch)
        }
        return sb.toString()
    }

    /** מספר בפורמט מקומי לצורך השוואה: 972501234567 -> 0501234567 */
    fun localDigits(number: String): String {
        val d = number.filter { it.isDigit() }
        return when {
            d.startsWith("972") && d.length > 9 -> "0" + d.substring(3)
            d.startsWith("00972") -> "0" + d.substring(5)
            else -> d
        }
    }

    /** מספר בינלאומי לוואטסאפ: 0501234567 -> 972501234567 */
    fun toInternational(number: String): String {
        val trimmed = number.trim()
        val d = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith("+") -> d
            d.startsWith("00") -> d.substring(2)
            d.startsWith("972") -> d
            d.startsWith("0") -> "972" + d.substring(1)
            else -> d
        }
    }

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun isDefaultDialer(ctx: Context): Boolean {
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return false
        return tm.defaultDialerPackage == ctx.packageName
    }

    fun placeCall(ctx: Context, rawNumber: String) {
        val number = cleanNumber(rawNumber)
        if (number.isEmpty()) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            toast(ctx, "אין הרשאת חיוג — אשר הרשאות באפליקציה")
            return
        }
        val uri = Uri.fromParts("tel", number, null)
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
                ?: throw IllegalStateException("no telecom")
            tm.placeCall(uri, Bundle())
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                toast(ctx, "החיוג נכשל")
            }
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** פותח צ'אט וואטסאפ עם המספר, עם הודעה כתובה מראש (אם יש) */
    fun openWhatsApp(ctx: Context, number: String, text: String?) {
        val phone = toInternational(number)
        if (phone.length < 6) {
            toast(ctx, "מספר לא תקין לוואטסאפ")
            return
        }
        val url = StringBuilder("https://api.whatsapp.com/send?phone=").append(phone)
        if (!text.isNullOrBlank()) url.append("&text=").append(Uri.encode(text))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            if (isInstalled(pm, pkg)) {
                intent.setPackage(pkg)
                break
            }
        }
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            intent.setPackage(null)
            try {
                ctx.startActivity(intent)
            } catch (e2: Exception) {
                toast(ctx, "וואטסאפ לא מותקן")
            }
        }
    }

    fun openSms(ctx: Context, number: String, text: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + cleanNumber(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!text.isNullOrBlank()) intent.putExtra("sms_body", text)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toast(ctx, "לא נמצאה אפליקציית הודעות")
        }
    }

    fun copyNumber(ctx: Context, number: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("number", number))
        toast(ctx, "המספר הועתק")
    }

    fun addToContacts(ctx: Context, number: String) {
        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            putExtra(ContactsContract.Intents.Insert.PHONE, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toast(ctx, "לא נמצאה אפליקציית אנשי קשר")
        }
    }

    fun openContact(ctx: Context, contactId: Long) {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId.toString())
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(ctx, "לא ניתן לפתוח את איש הקשר")
        }
    }

    fun blockNumber(ctx: Context, number: String) {
        try {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
            }
            ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
            toast(ctx, "המספר נחסם")
        } catch (e: Exception) {
            toast(ctx, "חסימה אפשרית רק כשהאפליקציה מוגדרת כחייגן ברירת מחדל")
        }
    }
}
