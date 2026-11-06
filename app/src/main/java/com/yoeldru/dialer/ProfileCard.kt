// נכתב: 2026-11-06 15:40 (שעון ישראל) — חייגן משופר: איתור תמונת "כרטיס פרופיל" (סמסונג) של איש קשר
package com.yoeldru.dialer

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import java.io.File

object ProfileCard {

    /** סוגי נתונים רגילים של אנדרואיד — כרטיס הפרופיל של סמסונג נשמר בסוג אחר */
    private val standardMimes = setOf(
        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE
    )

    private val cols = (1..15).map { "data$it" }.toTypedArray()

    private val cache = HashMap<Long, Bitmap?>()

    /** תמונת כרטיס הפרופיל אם יש, אחרת null */
    fun find(ctx: Context, contactId: Long, maxWidth: Int): Bitmap? {
        synchronized(cache) { if (cache.containsKey(contactId)) return cache[contactId] }
        var best: Bitmap? = null
        try {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE) + cols,
                ContactsContract.Data.CONTACT_ID + "=?", arrayOf(contactId.toString()), null
            )?.use { c ->
                while (c.moveToNext() && best == null) {
                    val mime = c.getString(0) ?: continue
                    if (mime in standardMimes) continue
                    // עודכן: 2026-11-06 16:20 — סמסונג: data2/data3 = מזהי קבצי תמונה (display_photo), data1 = JSON עם חיתוך
                    if (mime.endsWith("profile_card")) {
                        best = fromSamsungCard(ctx, c, maxWidth)
                        if (best != null) break
                    }
                    for (i in 1..15) {
                        val bmp = tryColumn(ctx, c, i, maxWidth)
                        if (bmp != null && bmp.width >= 200 && bmp.height >= 200) {
                            best = bmp
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        synchronized(cache) { cache[contactId] = best }
        return best
    }

    /** כרטיס פרופיל של סמסונג: התמונה שמורה כקובץ display_photo לפי מזהה ב-data3 / data2 */
    private fun fromSamsungCard(ctx: Context, c: Cursor, maxWidth: Int): Bitmap? {
        var crop: IntArray? = null
        try {
            val json = org.json.JSONObject(c.getString(1) ?: "")
            val arr = json.optJSONArray("crop_rect")
            if (arr != null && arr.length() == 4) crop = IntArray(4) { arr.getInt(it) }
        } catch (e: Exception) {
        }
        val ids = listOf(3, 2).mapNotNull { i ->
            try {
                if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER || c.getType(i) == Cursor.FIELD_TYPE_STRING) c.getString(i)?.toLongOrNull() else null
            } catch (e: Exception) {
                null
            }
        }
        for (id in ids) {
            val uri = android.content.ContentUris.withAppendedId(ContactsContract.DisplayPhoto.CONTENT_URI, id)
            val full = try {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            } ?: continue
            var bmp: Bitmap = full
            // אם התמונה גדולה מאזור החיתוך — חותכים כמו בכרטיס
            val cr = crop
            if (cr != null) {
                val l = cr[0].coerceAtLeast(0)
                val t = cr[1].coerceAtLeast(0)
                val r = cr[2].coerceAtMost(full.width)
                val b = cr[3].coerceAtMost(full.height)
                val alreadyCropped = full.width <= (cr[2] - cr[0]) + 2 && full.height <= (cr[3] - cr[1]) + 2
                if (!alreadyCropped && r - l > 100 && b - t > 100) {
                    bmp = Bitmap.createBitmap(full, l, t, r - l, b - t)
                }
            }
            // הקטנה לרוחב המסך
            if (bmp.width > maxWidth * 1.5) {
                val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt()
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
            }
            if (bmp.width >= 200 && bmp.height >= 200) return bmp
        }
        return null
    }

    private fun tryColumn(ctx: Context, c: Cursor, i: Int, maxWidth: Int): Bitmap? {
        return try {
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_BLOB -> {
                    val bytes = c.getBlob(i) ?: return null
                    if (bytes.size < 8_000) null else decodeBytes(bytes, maxWidth)
                }
                Cursor.FIELD_TYPE_STRING -> {
                    val s = c.getString(i)?.trim() ?: return null
                    when {
                        s.startsWith("content://") -> decodeStream(ctx, Uri.parse(s), maxWidth)
                        s.startsWith("file://") -> decodeFile(Uri.parse(s).path ?: return null, maxWidth)
                        s.startsWith("/") && looksLikeImage(s) -> decodeFile(s, maxWidth)
                        else -> null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun looksLikeImage(s: String): Boolean {
        val l = s.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".heic").any { l.endsWith(it) }
    }

    private fun sample(w: Int, maxWidth: Int): Int {
        var s = 1
        while (w / (s * 2) >= maxWidth) s *= 2
        return s
    }

    private fun decodeBytes(b: ByteArray, maxWidth: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, maxWidth) })
    }

    private fun decodeFile(path: String, maxWidth: Int): Bitmap? {
        if (!File(path).canRead()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, maxWidth) })
    }

    private fun decodeStream(ctx: Context, uri: Uri, maxWidth: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, maxWidth) })
        }
    }

    /** דוח טכני: אילו נתונים לא-רגילים שמורים לאיש הקשר (כדי לאתר איפה סמסונג שומרת את הכרטיס) */
    fun debugReport(ctx: Context, contactId: Long): String {
        val sb = StringBuilder()
        try {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE) + cols,
                ContactsContract.Data.CONTACT_ID + "=?", arrayOf(contactId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(0) ?: continue
                    val std = mime in standardMimes
                    sb.append(if (std) "• " else "★ ").append(mime.substringAfterLast('/')).append('\n')
                    if (std && mime != ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE) continue
                    for (i in 1..15) {
                        val desc = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_BLOB -> "blob ${c.getBlob(i)?.size ?: 0}B"
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)?.take(60)
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
                            else -> null
                        } ?: continue
                        sb.append("    data$i: ").append(desc).append('\n')
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("שגיאה: ").append(e.message)
        }
        val found = find(ctx, contactId, 1080)
        sb.append('\n').append(if (found != null) "✔ נמצאה תמונת כרטיס (${found.width}×${found.height})" else "✖ לא נמצאה תמונת כרטיס")
        return sb.toString()
    }
}
