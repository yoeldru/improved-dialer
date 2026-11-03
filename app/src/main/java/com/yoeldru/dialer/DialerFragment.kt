// נכתב: 2026-11-01 11:05 (שעון ישראל) — חייגן משופר: מסך חייגן T9 + שיחות אחרונות
package com.yoeldru.dialer

import android.content.ClipboardManager
import android.os.Bundle
import android.provider.CallLog
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DialerFragment : Fragment(R.layout.fragment_dialer) {

    private lateinit var display: TextView
    private lateinit var header: TextView
    private lateinit var emptyText: TextView
    private lateinit var results: RecyclerView
    private lateinit var adapter: RowAdapter

    private val typed = StringBuilder()
    private var recents: List<RecentCall> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        display = view.findViewById(R.id.display)
        header = view.findViewById(R.id.listHeader)
        emptyText = view.findViewById(R.id.emptyText)
        results = view.findViewById(R.id.results)

        adapter = RowAdapter(
            onClick = { row -> PhoneUtils.placeCall(requireContext(), row.number) },
            onLong = { row, anchor ->
                Dialogs.rowMenu(requireContext(), anchor, row, onChanged = { reload() }, onEdit = { n -> setTyped(n) })
            },
            onWhatsApp = { row -> PhoneUtils.openWhatsApp(requireContext(), row.number, null) }
        )
        results.layoutManager = LinearLayoutManager(requireContext())
        results.adapter = adapter

        Keypad.build(
            view.findViewById<LinearLayout>(R.id.keypad),
            showLetters = true,
            textColor = null,
            onKey = { k -> append(k) },
            onLongKey = { k ->
                if (k == '0') {
                    append('+')
                    true
                } else false
            }
        )

        // נכתב: 2026-11-03 14:40 — X מנקה הכל
        view.findViewById<ImageButton>(R.id.btnClear).setOnClickListener {
            typed.setLength(0)
            refresh()
        }

        val backspace = view.findViewById<ImageButton>(R.id.btnBackspace)
        backspace.setOnClickListener {
            if (typed.isNotEmpty()) {
                typed.deleteCharAt(typed.length - 1)
                refresh()
            }
        }
        backspace.setOnLongClickListener {
            typed.setLength(0)
            refresh()
            true
        }

        // לחיצה ארוכה על המספר = הדבקה
        display.setOnLongClickListener {
            val cm = requireContext().getSystemService(ClipboardManager::class.java)
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            if (!text.isNullOrBlank()) setTyped(PhoneUtils.cleanNumber(text))
            true
        }

        view.findViewById<FloatingActionButton>(R.id.btnCall).setOnClickListener {
            if (typed.isEmpty()) {
                // חיוג חוזר: ממלא את המספר האחרון שחויג
                val last = recents.firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE } ?: recents.firstOrNull()
                if (last != null) setTyped(last.number)
            } else {
                PhoneUtils.placeCall(requireContext(), typed.toString())
            }
        }
        view.findViewById<ImageButton>(R.id.btnWhatsApp).setOnClickListener {
            if (typed.isEmpty()) PhoneUtils.toast(requireContext(), "הקלד מספר כדי לפתוח צ'אט בוואטסאפ")
            else PhoneUtils.openWhatsApp(requireContext(), typed.toString(), null)
        }
        val mic = view.findViewById<ImageButton>(R.id.btnMic)
        mic.setOnClickListener { (activity as? MainActivity)?.startVoiceDial() }
        // לחיצה ארוכה על המיקרופון = הדלקה/כיבוי מצב נהיגה
        mic.setOnLongClickListener {
            (activity as? MainActivity)?.toggleDrivingMode()
            true
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        MainActivity.pendingDial?.let {
            MainActivity.pendingDial = null
            setTyped(it)
        }
        reload()
    }

    fun setTyped(number: String) {
        typed.setLength(0)
        typed.append(number)
        refresh()
    }

    private fun append(k: Char) {
        typed.append(k)
        refresh()
    }

    fun reload() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                val phones = ContactsRepo.load(ctx)
                CallLogRepo.load(ctx, phones)
            }
            recents = r
            refresh()
        }
    }

    private fun refresh() {
        display.text = typed.toString()
        view?.findViewById<View>(R.id.btnClear)?.visibility = if (typed.isEmpty()) View.INVISIBLE else View.VISIBLE
        val q = typed.toString()
        if (q.isEmpty()) {
            header.text = "שיחות אחרונות"
            val now = System.currentTimeMillis()
            adapter.items = recents.map { rc ->
                val arrow = when (rc.type) {
                    CallLog.Calls.INCOMING_TYPE -> "↙ נכנסת"
                    CallLog.Calls.OUTGOING_TYPE -> "↗ יוצאת"
                    CallLog.Calls.MISSED_TYPE -> "✕ שלא נענתה"
                    CallLog.Calls.REJECTED_TYPE -> "⊘ נדחתה"
                    CallLog.Calls.BLOCKED_TYPE -> "🚫 חסומה"
                    else -> "•"
                }
                val count = if (rc.count > 1) " (${rc.count})" else ""
                val time = DateUtils.getRelativeTimeSpanString(rc.date, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                val title = (rc.name ?: rc.number.ifEmpty { "מספר חסוי" }) + count
                // נכתב: 2026-11-02 22:40 — משך השיחה בשורה (רק לשיחות שנענו)
                val dur = formatDuration(rc.durationSec)
                val parts = mutableListOf(arrow)
                if (rc.name != null) parts.add(rc.number)
                parts.add(time.toString())
                if (dur != null) parts.add("⏱ $dur")
                val sub = parts.joinToString(" · ")
                Row(title, sub, rc.number, rc.photo, rc.contactId, rc.type == CallLog.Calls.MISSED_TYPE)
            }
            emptyText.visibility = if (recents.isEmpty()) View.VISIBLE else View.GONE
            emptyText.text = if (ContactsRepo.hasPermission(requireContext())) "אין שיחות אחרונות" else "יש לאשר הרשאות כדי לראות שיחות ואנשי קשר"
        } else {
            header.text = "תוצאות חיפוש"
            val matches = search(q)
            adapter.items = matches.map { p -> Row(p.name, p.label + " · " + p.number, p.number, p.photo, p.contactId) }
            emptyText.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
            emptyText.text = "לא נמצאו אנשי קשר — לחץ על החיוג כדי לחייג למספר"
        }
        if (adapter.itemCount > 0) results.scrollToPosition(0)
    }

    /** 45 -> "45 שנ׳", 205 -> "3:25", 3725 -> "1:02:05"; 0 -> null */
    private fun formatDuration(sec: Long): String? {
        if (sec <= 0) return null
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> String.format("%d:%02d:%02d", h, m, s)
            m > 0 -> String.format("%d:%02d", m, s)
            else -> "$s שנ׳"
        }
    }

    private fun search(q: String): List<PhoneEntry> {
        val digits = q.filter { it.isDigit() }
        val localQ = PhoneUtils.localDigits(q)
        val scored = ArrayList<Pair<PhoneEntry, Int>>()
        for (p in ContactsRepo.phones) {
            var s = T9.matchScore(p.t9Words, q) * 10
            if (s == 0 && digits.length >= 2 && (p.local.contains(localQ) || p.number.filter { it.isDigit() }.contains(digits))) {
                s = if (p.local.startsWith(localQ)) 5 else 4
            }
            if (s > 0) scored.add(p to s)
        }
        return scored.sortedWith(compareByDescending<Pair<PhoneEntry, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
            .take(80)
    }
}
