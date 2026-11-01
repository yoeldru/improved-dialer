// נכתב: 2026-11-01 22:55 (שעון ישראל) — חייגן משופר: רשימת הקלטות השיחה (ניגון, שיתוף, מחיקה)
package com.yoeldru.dialer

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsActivity : AppCompatActivity() {

    private class Rec(val uri: Uri, val name: String, val date: Long, val durationMs: Long)

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private var items: List<Rec> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "הקלטות שיחה"
        val outer = android.widget.LinearLayout(this)
        outer.orientation = android.widget.LinearLayout.VERTICAL
        outer.fitsSystemWindows = true
        outer.addView(TextView(this).apply {
            text = "📼 הקלטות שיחה"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(48, 36, 48, 16)
        })
        val root = FrameLayout(this)
        outer.addView(root, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        empty = TextView(this).apply {
            text = "אין הקלטות עדיין.\nבזמן שיחה לחץ ⏺ הקלט."
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(40, 40, 40, 40)
        }
        root.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(outer)
        list.adapter = RowAdapter(
            onClick = { row -> play(Uri.parse(row.number)) },
            onLong = { row, anchor -> menu(Uri.parse(row.number), anchor) },
            onWhatsApp = { row -> share(Uri.parse(row.number)) }
        )
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { query() }
            val now = System.currentTimeMillis()
            (list.adapter as RowAdapter).items = items.map { r ->
                val dur = DateUtils.formatElapsedTime(r.durationMs / 1000)
                val time = DateUtils.getRelativeTimeSpanString(r.date, now, DateUtils.MINUTE_IN_MILLIS)
                Row(r.name.removeSuffix(".m4a").replace('_', ' '), "$time · $dur", r.uri.toString(), null, null)
            }
            empty.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun query(): List<Rec> {
        val out = ArrayList<Rec>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION
        )
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?", arrayOf(RecordingService.FOLDER + "%"),
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(
                        Rec(
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: "", c.getLong(2) * 1000, c.getLong(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun play(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            PhoneUtils.toast(this, "לא נמצא נגן")
        }
    }

    private fun share(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "שתף הקלטה"))
    }

    private fun menu(uri: Uri, anchor: android.view.View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "▶ נגן")
        m.menu.add(0, 2, 1, "📤 שתף")
        m.menu.add(0, 3, 2, "🗑 מחק")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> play(uri)
                2 -> share(uri)
                3 -> MaterialAlertDialogBuilder(this)
                    .setTitle("למחוק את ההקלטה?")
                    .setPositiveButton("מחק") { _, _ ->
                        try {
                            contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            PhoneUtils.toast(this, "לא ניתן למחוק")
                        }
                        load()
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
            true
        }
        m.show()
    }
}
