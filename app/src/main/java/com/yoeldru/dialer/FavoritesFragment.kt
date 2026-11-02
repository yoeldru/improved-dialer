// נכתב: 2026-11-01 11:05 (שעון ישראל) — חייגן משופר: מסך מועדפים עם תמונות (לחיצה = חיוג)
// עודכן: 2026-11-01 13:20 — מצב סידור: לחיצה ארוכה וגרירה, הסדר נשמר
// עודכן: 2026-11-02 20:20 — מצב סידור כרשימה עם חיצי למעלה/למטה, בסיום חוזר לעיגולים
package com.yoeldru.dialer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesFragment : Fragment(R.layout.fragment_favorites) {

    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var hint: TextView
    private lateinit var arrangeBtn: MaterialButton
    private val adapter = FavAdapter()
    private var arranging = false

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun isLongPressDragEnabled(): Boolean = arranging

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (!arranging) 0 else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                vh.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                vh.itemView.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.85f).setDuration(120).start()
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            saveOrder()
        }
    })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        grid = view.findViewById(R.id.favGrid)
        empty = view.findViewById(R.id.favEmpty)
        hint = view.findViewById(R.id.favHint)
        arrangeBtn = view.findViewById(R.id.btnArrange)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter
        touchHelper.attachToRecyclerView(grid)
        arrangeBtn.setOnClickListener { setArranging(!arranging) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onPause() {
        if (arranging) setArranging(false)
        super.onPause()
    }

    private fun setArranging(on: Boolean) {
        arranging = on
        arrangeBtn.text = if (on) "✔ סיום" else "↕ סדר"
        hint.text = if (on) "▲▼ להזזה, או גרירה של ☰" else "לחיצה = חיוג · לחיצה ארוכה = אפשרויות"
        (activity as? MainActivity)?.setPagerSwipe(!on)
        if (!on) {
            saveOrder()
            PhoneUtils.toast(requireContext(), "הסדר נשמר ✔")
        }
        // רשימה בזמן סידור, עיגולים בסיום
        grid.layoutManager = if (on) LinearLayoutManager(requireContext()) else GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter
        adapter.notifyDataSetChanged()
    }

    private fun saveOrder() {
        Prefs.setFavOrder(requireContext(), adapter.items.map { it.contactId })
    }

    private fun reload() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val favs = withContext(Dispatchers.IO) {
                if (ContactsRepo.phones.isEmpty()) ContactsRepo.load(ctx)
                val list = ContactsRepo.loadFavorites(ctx)
                // סידור לפי הסדר השמור; מועדפים חדשים בסוף
                val order = Prefs.favOrder(ctx)
                val pos = HashMap<Long, Int>()
                order.forEachIndexed { i, id -> pos[id] = i }
                list.sortedWith(compareBy<Favorite> { pos[it.contactId] ?: Int.MAX_VALUE }.thenBy { it.name })
            }
            adapter.items = favs.toMutableList()
            empty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
            arrangeBtn.visibility = if (favs.size > 1) View.VISIBLE else View.GONE
        }
    }

    private fun onFavClick(f: Favorite) {
        Dialogs.withNumber(requireContext(), f.contactId, f.name) { n -> PhoneUtils.placeCall(requireContext(), n) }
    }

    private fun onFavLong(f: Favorite, anchor: View) {
        val ctx = requireContext()
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "💬 וואטסאפ")
        menu.menu.add(0, 2, 1, "✉ SMS")
        menu.menu.add(0, 3, 2, "👤 פתח איש קשר")
        if (ContactsRepo.numbersOf(f.contactId).size > 1) menu.menu.add(0, 6, 3, "📌 מספר ברירת מחדל")
        menu.menu.add(0, 5, 3, "↕ סדר מועדפים")
        menu.menu.add(0, 4, 4, "☆ הסר מהמועדפים")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> Dialogs.withNumber(ctx, f.contactId, f.name) { n -> PhoneUtils.openWhatsApp(ctx, n, null) }
                2 -> Dialogs.withNumber(ctx, f.contactId, f.name) { n -> PhoneUtils.openSms(ctx, n) }
                3 -> PhoneUtils.openContact(ctx, f.contactId)
                4 -> {
                    ContactsRepo.setStarred(ctx, f.contactId, false)
                    reload()
                }
                5 -> setArranging(true)
                6 -> Dialogs.chooseDefault(ctx, f.contactId, f.name)
            }
            true
        }
        menu.show()
    }

    inner class FavAdapter : RecyclerView.Adapter<FavAdapter.VH>() {
        var items: MutableList<Favorite> = mutableListOf()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        fun move(from: Int, to: Int) {
            if (from == to || to < 0 || to >= items.size) return
            val item = items.removeAt(from)
            items.add(to, item)
            notifyItemMoved(from, to)
            // עדכון המספרים ליד השמות
            notifyItemRangeChanged(minOf(from, to), Math.abs(from - to) + 1, "pos")
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val photo: ImageView = v.findViewById(R.id.photo)
            val initial: TextView = v.findViewById(R.id.initial)
            val name: TextView = v.findViewById(R.id.name)
            val posText: TextView? = v.findViewById(R.id.position)
            val up: View? = v.findViewById(R.id.btnUp)
            val down: View? = v.findViewById(R.id.btnDown)
            val handle: View? = v.findViewById(R.id.handle)
        }

        override fun getItemViewType(position: Int): Int = if (arranging) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.item_fav_row else R.layout.item_favorite, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = items[position]
            Avatar.bind(h.photo, h.initial, f.name, f.photo)
            h.name.text = f.name
            if (arranging) {
                h.posText?.text = (position + 1).toString()
                h.itemView.setOnClickListener(null)
                h.itemView.setOnLongClickListener(null)
                h.itemView.isLongClickable = false
                h.up?.alpha = if (position == 0) 0.25f else 1f
                h.down?.alpha = if (position == items.size - 1) 0.25f else 1f
                h.up?.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p > 0) {
                        move(p, p - 1)
                        grid.scrollToPosition(p - 1)
                        saveOrder()
                    }
                }
                h.down?.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION && p < items.size - 1) {
                        move(p, p + 1)
                        grid.scrollToPosition(p + 1)
                        saveOrder()
                    }
                }
                // נגיעה בידית ☰ מתחילה גרירה מיד
                h.handle?.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                    false
                }
            } else {
                h.itemView.setOnClickListener { onFavClick(f) }
                h.itemView.setOnLongClickListener {
                    onFavLong(f, it)
                    true
                }
            }
        }
    }
}
