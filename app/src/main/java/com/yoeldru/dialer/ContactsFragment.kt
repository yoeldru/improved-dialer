// נכתב: 2026-11-01 23:05 (שעון ישראל) — חייגן משופר: רשימת אנשי קשר עם חיפוש חופשי (שם / מספר)
package com.yoeldru.dialer

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsFragment : Fragment(R.layout.fragment_contacts) {

    private lateinit var search: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private lateinit var adapter: RowAdapter

    /** איש קשר אחד = שורה אחת (גם אם יש לו כמה מספרים) */
    private class ContactItem(val id: Long, val name: String, val photo: String?, val numbers: List<PhoneEntry>) {
        val norm: String = VoiceMatcher.normalize(name)
        val digits: List<String> = numbers.map { it.local }
    }

    private var all: List<ContactItem> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        search = view.findViewById(R.id.searchBox)
        list = view.findViewById(R.id.contactsList)
        empty = view.findViewById(R.id.contactsEmpty)
        count = view.findViewById(R.id.countText)

        adapter = RowAdapter(
            onClick = { row ->
                row.contactId?.let { id ->
                    Dialogs.withNumber(requireContext(), id, row.title) { n -> PhoneUtils.placeCall(requireContext(), n) }
                }
            },
            onLong = { row, anchor ->
                Dialogs.rowMenu(requireContext(), anchor, row, onChanged = { reload() }, onEdit = { n ->
                    MainActivity.pendingDial = n
                    (activity as? MainActivity)?.showDialer()
                })
            },
            onWhatsApp = { row ->
                row.contactId?.let { id ->
                    Dialogs.withNumber(requireContext(), id, row.title) { n -> PhoneUtils.openWhatsApp(requireContext(), n, null) }
                }
            }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        // גלילה מסתירה את המקלדת
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter()
        })

        view.findViewById<ExtendedFloatingActionButton>(R.id.btnNewContact).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                    val q = search.text?.toString()?.trim() ?: ""
                    if (q.isNotEmpty()) {
                        if (q.any { it.isDigit() } && q.all { it.isDigit() || it in "+-() " }) {
                            putExtra(ContactsContract.Intents.Insert.PHONE, q)
                        } else {
                            putExtra(ContactsContract.Intents.Insert.NAME, q)
                        }
                    }
                })
            } catch (e: Exception) {
                PhoneUtils.toast(requireContext(), "לא נמצאה אפליקציית אנשי קשר")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onPause() {
        hideKeyboard()
        super.onPause()
    }

    private fun hideKeyboard() {
        val imm = context?.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun reload() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            all = withContext(Dispatchers.IO) {
                val phones = if (ContactsRepo.phones.isEmpty()) ContactsRepo.load(ctx) else ContactsRepo.phones
                phones.groupBy { it.contactId }
                    .map { (id, nums) -> ContactItem(id, nums[0].name, nums.firstNotNullOfOrNull { it.photo }, nums) }
                    .sortedWith(compareBy<ContactItem> { sortKey(it.name) }.thenBy { it.name })
            }
            filter()
        }
    }

    /** עברית קודם, אחר כך אנגלית, אחר כך כל השאר */
    private fun sortKey(name: String): Int {
        val c = name.trim().firstOrNull() ?: return 3
        return when {
            c in 'א'..'ת' -> 0
            c.isLetter() -> 1
            else -> 2
        }
    }

    private fun filter() {
        if (!isAdded) return
        val raw = search.text?.toString()?.trim() ?: ""
        val q = VoiceMatcher.normalize(raw)
        val qWords = q.split(' ').filter { it.isNotEmpty() }
        val qDigits = PhoneUtils.localDigits(raw)
        val numeric = raw.isNotEmpty() && raw.all { it.isDigit() || it in "+-() " }

        val result = if (raw.isEmpty()) all else all.filter { c ->
            if (numeric) {
                qDigits.length >= 2 && c.digits.any { it.contains(qDigits) }
            } else {
                // כל מילה שהוקלדה צריכה להופיע (בתחילת מילה או בתוך השם)
                val nameWords = c.norm.split(' ')
                qWords.all { w -> nameWords.any { it.startsWith(w) } || c.norm.contains(w) }
            }
        }.sortedByDescending { c ->
            // מי שמתחיל במה שהוקלד — קודם
            if (!numeric && q.isNotEmpty() && c.norm.startsWith(q)) 1 else 0
        }

        adapter.items = result.map { c ->
            val first = c.numbers[0]
            val sub = if (c.numbers.size > 1) "${first.number}  ·  +${c.numbers.size - 1} מספרים" else first.label + " · " + first.number
            Row(c.name, sub, first.number, c.photo, c.id)
        }
        count.text = if (raw.isEmpty()) "${all.size} אנשי קשר" else "${result.size} תוצאות"
        empty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            !ContactsRepo.hasPermission(requireContext()) -> "יש לאשר הרשאת אנשי קשר"
            raw.isEmpty() -> "אין אנשי קשר"
            else -> "לא נמצא \"$raw\""
        }
        if (adapter.itemCount > 0) list.scrollToPosition(0)
    }
}
