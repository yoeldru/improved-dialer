// נכתב: 2026-11-01 10:55 (שעון ישראל) — חייגן משופר: טעינת אנשי קשר, מועדפים וזיהוי מתקשר
package com.yoeldru.dialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class PhoneEntry(
    val contactId: Long,
    val name: String,
    val number: String,
    val label: String,
    val photo: String?,
    val starred: Boolean,
    val type: Int = 0
) {
    val t9Words: List<String> by lazy { T9.wordsToT9(name) }
    val local: String by lazy { PhoneUtils.localDigits(number) }
}

class Favorite(val contactId: Long, val name: String, val photo: String?)

class CallerInfo(val name: String?, val photo: String?)

object ContactsRepo {
    @Volatile
    var phones: List<PhoneEntry> = emptyList()
        private set

    private val callerCache = HashMap<String, CallerInfo>()

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** טוען את כל מספרי הטלפון של אנשי הקשר (רץ ברקע) */
    fun load(ctx: Context): List<PhoneEntry> {
        if (!hasPermission(ctx)) return emptyList()
        val list = ArrayList<PhoneEntry>()
        val seen = HashSet<String>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    val num = c.getString(2) ?: continue
                    val key = id.toString() + "|" + PhoneUtils.localDigits(num)
                    if (!seen.add(key)) continue
                    val type = c.getInt(3)
                    val customLabel = c.getString(4)
                    val label = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(ctx.resources, type, customLabel).toString()
                    list.add(PhoneEntry(id, name, num, label, c.getString(5), c.getInt(6) == 1, type))
                }
            }
        } catch (e: Exception) {
            // נשאיר רשימה ריקה
        }
        phones = list
        synchronized(callerCache) { callerCache.clear() }
        return list
    }

    fun numbersOf(contactId: Long): List<PhoneEntry> = phones.filter { it.contactId == contactId }

    fun loadFavorites(ctx: Context): List<Favorite> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<Favorite>()
        val proj = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_URI
        )
        try {
            ctx.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, proj,
                ContactsContract.Contacts.STARRED + "=1 AND " + ContactsContract.Contacts.HAS_PHONE_NUMBER + "=1",
                null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(Favorite(c.getLong(0), c.getString(1) ?: "", c.getString(2)))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun setStarred(ctx: Context, contactId: Long, starred: Boolean): Boolean {
        return try {
            val values = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
            ctx.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI, values,
                ContactsContract.Contacts._ID + "=?", arrayOf(contactId.toString())
            ) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun isStarred(contactId: Long): Boolean = phones.any { it.contactId == contactId && it.starred }

    /** זיהוי מתקשר לפי מספר (עם מטמון) */
    fun lookup(ctx: Context, number: String): CallerInfo {
        if (number.isBlank()) return CallerInfo(null, null)
        synchronized(callerCache) { callerCache[number]?.let { return it } }
        var info = CallerInfo(null, null)
        if (hasPermission(ctx)) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) info = CallerInfo(c.getString(0), c.getString(1))
                }
            } catch (e: Exception) {
            }
        }
        synchronized(callerCache) { callerCache[number] = info }
        return info
    }
}
