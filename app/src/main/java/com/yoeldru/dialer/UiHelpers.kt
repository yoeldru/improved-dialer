// נכתב: 2026-11-01 11:00 (שעון ישראל) — חייגן משופר: רכיבי ממשק משותפים (תמונה, מקלדת, רשימות, תפריטים)
package com.yoeldru.dialer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
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
        onLongKey: (Char) -> Boolean,
        dark: Boolean = false
    ) {
        container.removeAllViews()
        val ctx = container.context
        val density = ctx.resources.displayMetrics.density
        val margin = (5 * density).toInt()
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
                cell.background = keyBackground(density, dark)
                cell.isClickable = true
                cell.isFocusable = true

                val digit = TextView(ctx)
                digit.text = k.toString()
                digit.textSize = 27f
                digit.gravity = Gravity.CENTER
                digit.includeFontPadding = false
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
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(margin, margin, margin, margin)
                row.addView(cell, lp)
            }
        }
    }

    // נכתב: 2026-11-01 13:10 — מסגרת מעוצבת לכל מקש: פינות מעוגלות, קו ירוק עדין ומילוי שקוף + אפקט לחיצה
    private fun keyBackground(density: Float, dark: Boolean): Drawable {
        val stroke = if (dark) 0x55FFFFFF else 0x991B8A5A.toInt()
        val fill = if (dark) 0x14FFFFFF else 0x121B8A5A
        val ripple = if (dark) 0x33FFFFFF else 0x331B8A5A
        val body = GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            cornerRadius = 18 * density
            setColor(fill)
            setStroke((1.5f * density).toInt().coerceAtLeast(2), stroke)
        }
        val maskShape = GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            cornerRadius = 18 * density
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), body, maskShape)
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

    /**
     * איש קשר עם כמה מספרים: אם נקבעה ברירת מחדל — משתמשים בה מיד; אחרת חלון בחירה
     * עם תיבת סימון "זכור כברירת מחדל". forcePick = תמיד להציג בחירה.
     * עודכן: 2026-11-02 20:05
     */
    fun withNumber(ctx: Context, contactId: Long, name: String, forcePick: Boolean = false, action: (String) -> Unit) {
        val nums = ContactsRepo.numbersOf(contactId)
        if (nums.isEmpty()) {
            PhoneUtils.toast(ctx, "לא נמצא מספר")
            return
        }
        if (nums.size == 1) {
            action(nums[0].number)
            return
        }
        if (!forcePick && ContactsRepo.hasExplicitDefault(ctx, contactId)) {
            ContactsRepo.defaultFor(ctx, contactId)?.let {
                action(it.number)
                return
            }
        }
        pickNumber(ctx, contactId, name, rememberChecked = false) { action(it.number) }
    }

    /** חלון בחירת מספר + "זכור כברירת מחדל" */
    fun pickNumber(
        ctx: Context, contactId: Long, name: String, rememberChecked: Boolean,
        title: String = name, onPick: (PhoneEntry) -> Unit
    ) {
        val nums = ContactsRepo.numbersOf(contactId)
        val current = if (ContactsRepo.hasExplicitDefault(ctx, contactId)) ContactsRepo.defaultFor(ctx, contactId) else null
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val rv = RecyclerView(ctx)
        rv.layoutManager = LinearLayoutManager(ctx)
        val check = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply {
            text = "📌 זכור כמספר ברירת מחדל ל" + name
            isChecked = rememberChecked
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad / 2, pad / 2, pad, pad / 2)
        }
        box.addView(rv)
        box.addView(check)
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("ביטול", null)
        if (current != null) {
            builder.setNeutralButton("נקה ברירת מחדל") { _, _ ->
                ContactsRepo.setDefault(ctx, contactId, null)
                PhoneUtils.toast(ctx, "ברירת המחדל נוקתה")
            }
        }
        val dialog = builder.create()
        val shown = nums.map { p ->
            if (current != null && p.local == current.local) PhoneEntry(p.contactId, p.name + " 📌", p.number, p.label, p.photo, p.starred, p.type, p.dataId, true) else p
        }
        rv.adapter = CandidateAdapter(shown) { picked ->
            dialog.dismiss()
            val real = nums.firstOrNull { it.dataId == picked.dataId && it.local == picked.local } ?: picked
            if (check.isChecked) {
                ContactsRepo.setDefault(ctx, contactId, real)
                PhoneUtils.toast(ctx, "📌 " + real.number + " נקבע כברירת מחדל")
            }
            onPick(real)
        }
        dialog.show()
    }

    /** קביעת מספר ברירת מחדל בלבד (בלי לחייג) */
    fun chooseDefault(ctx: Context, contactId: Long, name: String) {
        if (ContactsRepo.numbersOf(contactId).size < 2) {
            PhoneUtils.toast(ctx, "לאיש קשר זה יש מספר אחד בלבד")
            return
        }
        pickNumber(ctx, contactId, name, rememberChecked = true, title = "📌 מספר ברירת מחדל — $name") { }
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
            if (ContactsRepo.numbersOf(cid).size > 1) m.add(0, 9, 6, "📌 מספר ברירת מחדל")
            m.add(0, 10, 7, "🔎 בדיקת כרטיס פרופיל")
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
                9 -> if (cid != null) chooseDefault(ctx, cid, row.title)
                10 -> if (cid != null) {
                    // נכתב: 2026-11-06 15:45 — דוח טכני לאיתור תמונת כרטיס הפרופיל
                    val report = ProfileCard.debugReport(ctx, cid)
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("🔎 " + row.title)
                        .setMessage(report)
                        .setPositiveButton("סגור", null)
                        .setNeutralButton("העתק") { _, _ ->
                            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("report", report))
                            PhoneUtils.toast(ctx, "הועתק")
                        }
                        .show()
                }
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
