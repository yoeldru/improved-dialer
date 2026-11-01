// נכתב: 2026-11-01 11:05 (שעון ישראל) — חייגן משופר: מסך מועדפים עם תמונות (לחיצה = חיוג)
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
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesFragment : Fragment(R.layout.fragment_favorites) {

    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private val adapter = FavAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        grid = view.findViewById(R.id.favGrid)
        empty = view.findViewById(R.id.favEmpty)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val favs = withContext(Dispatchers.IO) {
                if (ContactsRepo.phones.isEmpty()) ContactsRepo.load(ctx)
                ContactsRepo.loadFavorites(ctx)
            }
            adapter.items = favs
            empty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
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
        menu.menu.add(0, 4, 3, "☆ הסר מהמועדפים")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> Dialogs.withNumber(ctx, f.contactId, f.name) { n -> PhoneUtils.openWhatsApp(ctx, n, null) }
                2 -> Dialogs.withNumber(ctx, f.contactId, f.name) { n -> PhoneUtils.openSms(ctx, n) }
                3 -> PhoneUtils.openContact(ctx, f.contactId)
                4 -> {
                    ContactsRepo.setStarred(ctx, f.contactId, false)
                    reload()
                }
            }
            true
        }
        menu.show()
    }

    inner class FavAdapter : RecyclerView.Adapter<FavAdapter.VH>() {
        var items: List<Favorite> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val photo: ImageView = v.findViewById(R.id.photo)
            val initial: TextView = v.findViewById(R.id.initial)
            val name: TextView = v.findViewById(R.id.name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = items[position]
            Avatar.bind(h.photo, h.initial, f.name, f.photo)
            h.name.text = f.name
            h.itemView.setOnClickListener { onFavClick(f) }
            h.itemView.setOnLongClickListener {
                onFavLong(f, it)
                true
            }
        }
    }
}
