// נכתב: 2026-11-01 11:00 (שעון ישראל) — חייגן משופר: רכיבי ממשק משותפים (תמונה, מקלדת, רשימות, תפריטים)
package com.yoeldru.dialer

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Avatar {
    private val colors = intArrayOf(
        0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(), 0xFFAB47BC.toInt(),
        0xFF42A5F5.toInt(), 0xFFFF7043.toInt(), 0xFF8D6E63.toInt(), 0xFF66BB6A.toInt()
    )

    fun bind(img: ImageView, initial: TextView, name: String, photo: String?) {
        var shown = false
        if (!photo.isNullOrEmpty()) {
            try {
                img.setImageURI(null)
                img.setImageURI(Uri.parse(photo))
                shown = img.drawable != null
            } catch (e: Exception) {
                shown = false
            }
        }
        if (shown) {
            img.visibility = View.VISIBLE
            initial.visibility = View.GONE
        } else {
            img.visibility = View.GONE
            initial.visibility = View.VISIBLE
            val first = name.trim().firstOrNull { it.isLetterOrDigit() }
            initial.text = first?.toString() ?: "#"
            initial.backgroundTintList = ColorStateList.valueOf(colors[Math.floorMod(name.hashCode(), colors.size)])
        }
    }
}

object Keypad {
    private val keys = "123456789*0#"

    fun build(
        container: LinearLayout,
        showLetters: Boolean,
        textColor: Int?,
        onKey: (Char) -> Unit,
        onLongKey: (Char) -> Boolean
    ) {
        container.removeAllViews()
        val ctx = container.context
        val bg = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, bg, true)
        for (r in 0 until 4) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutDirection = View.LAYOUT_DIRECTION_LTR
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val cell = LinearLayout(ctx)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.setBackgroundResource(bg.resourceId)
                cell.isClickable = true
                cell.isFocusable = true

                val digit = TextView(ctx)
                digit.text = k.toString()
                digit.textSize = 28f
                digit.gravity = Gravity.CENTER
                if (textColor != null) digit.setTextColor(textColor)
                cell.addView(digit)

                if (showLetters) {
                    val letters = TextView(ctx)
                    letters.text = T9.keyLetters[k] ?: ""
                    letters.textSize = 10f
                    letters.alpha = 0.65f
                    letters.gravity = Gravity.CENTER
                    if (textColor != null) letters.setTextColor(textColor)
                    cell.addView(letters)
                }

                cell.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onKey(k)
                }
                cell.setOnLongClickListener { onLongKey(k) }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }
    }
}

/** שורה ברשימה: איש קשר או שיחה אחרונה */
class Row(
    val title: String,
    val subtitle: String,
    val number: String,
    val photo: String?,
    val contactId: Long?,
    val missed: Boolean = false
)

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onLong: (Row, View) -> Unit,
    private val onWhatsApp: (Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    var items: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ImageView = v.findViewById(R.id.photo)
        val initial: TextView = v.findViewById(R.id.initial)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val wa: ImageButton = v.findViewById(R.id.btnWa)
        var defaultColor: Int = title.currentTextColor
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = items[position]
        Avatar.bind(h.photo, h.initial, row.title, row.photo)
        h.title.text = row.title
        h.subtitle.text = row.subtitle
        if (row.missed) h.title.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.missed))
        else h.title.setTextColor(h.defaultColor)
        h.itemView.setOnClickListener { onClick(row) }
        h.itemView.setOnLongClickListener {
            onLong(row, it)
            true
        }
        h.wa.setOnClickListener { onWhatsApp(row) }
    }
}

/** רשימה גדולה של אפשרויות (חיוג קולי / בחירת מספר) */
class CandidateAdapter(
    private val items: List<PhoneEntry>,
    private val onPick: (PhoneEntry) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ImageView = v.findViewById(R.id.photo)
        val initial: TextView = v.findViewById(R.id.initial)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_candidate, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        Avatar.bind(h.photo, h.initial, p.name, p.photo)
        h.title.text = p.name
        h.subtitle.text = p.label + " · " + p.number
        h.itemView.setOnClickListener { onPick(p) }
    }
}

object Dialogs {

    /** חלון עם אפשרויות גדולות; לחיצה על אחת מבצעת את הפעולה */
    fun showCandidates(ctx: Context, title: String, items: List<PhoneEntry>, onPick: (PhoneEntry) -> Unit) {
        val rv = RecyclerView(ctx)
        rv.layoutManager = LinearLayoutManager(ctx)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(rv)
            .setNegativeButton("ביטול", null)
            .create()
        rv.adapter = CandidateAdapter(items) {
            dialog.dismiss()
            onPick(it)
        }
        dialog.show()
    }

    /** איש קשר עם כמה מספרים — בחירת מספר */
    fun withNumber(ctx: Context, contactId: Long, name: String, action: (String) -> Unit) {
        val nums = ContactsRepo.numbersOf(contactId)
        when {
            nums.isEmpty() -> PhoneUtils.toast(ctx, "לא נמצא מספר")
            nums.size == 1 -> action(nums[0].number)
            else -> showCandidates(ctx, name, nums) { action(it.number) }
        }
    }

    /** תפריט לחיצה ארוכה על שורה */
    fun rowMenu(ctx: Context, anchor: View, row: Row, onChanged: () -> Unit, onEdit: (String) -> Unit) {
        val menu = PopupMenu(ctx, anchor)
        val m = menu.menu
        m.add(0, 1, 0, "💬 וואטסאפ")
        m.add(0, 2, 1, "✉ SMS")
        m.add(0, 3, 2, "✏ ערוך לפני חיוג")
        m.add(0, 4, 3, "📋 העתק מספר")
        val cid = row.contactId
        if (cid != null) {
            m.add(0, 5, 4, if (ContactsRepo.isStarred(cid)) "☆ הסר מהמועדפים" else "⭐ הוסף למועדפים")
            m.add(0, 6, 5, "👤 פתח איש קשר")
        } else {
            m.add(0, 7, 4, "➕ הוסף לאנשי קשר")
        }
        m.add(0, 8, 6, "🚫 חסום מספר")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> PhoneUtils.openWhatsApp(ctx, row.number, null)
                2 -> PhoneUtils.openSms(ctx, row.number)
                3 -> onEdit(row.number)
                4 -> PhoneUtils.copyNumber(ctx, row.number)
                5 -> if (cid != null) {
                    val newState = !ContactsRepo.isStarred(cid)
                    if (ContactsRepo.setStarred(ctx, cid, newState)) {
                        PhoneUtils.toast(ctx, if (newState) "נוסף למועדפים" else "הוסר מהמועדפים")
                        onChanged()
                    } else {
                        PhoneUtils.toast(ctx, "לא הצלחתי לעדכן — חסרה הרשאת עריכת אנשי קשר")
                    }
                }
                6 -> if (cid != null) PhoneUtils.openContact(ctx, cid)
                7 -> PhoneUtils.addToContacts(ctx, row.number)
                8 -> MaterialAlertDialogBuilder(ctx)
                    .setTitle("לחסום את " + row.number + "?")
                    .setPositiveButton("חסום") { _, _ -> PhoneUtils.blockNumber(ctx, row.number) }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
            true
        }
        menu.show()
    }
}
