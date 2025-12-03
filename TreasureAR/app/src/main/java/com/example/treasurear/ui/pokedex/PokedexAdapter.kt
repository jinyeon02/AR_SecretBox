package com.example.treasurear.ui.pokedex

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.treasurear.R
import com.example.treasurear.ui.model.UiItem

private const val TYPE_HEADER = 0
private const val TYPE_SUBZONE = 1

class PokedexAdapter(
    private val items: List<UiItem>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // ⭐ 외부에서 items 읽을 수 있게 공개 getter
    val publicItems get() = items

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val header: TextView = view.findViewById(R.id.headerText)
    }

    class SubZoneHolder(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.subzoneImage)
        val title: TextView = view.findViewById(R.id.subzoneTitle)
        val count: TextView = view.findViewById(R.id.subzoneCount)
    }

    override fun getItemViewType(position: Int): Int =
        when (items[position]) {
            is UiItem.Header -> TYPE_HEADER
            is UiItem.SubZone -> TYPE_SUBZONE
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_header, parent, false)
            HeaderHolder(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subzone, parent, false)
            SubZoneHolder(v)
        }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {

            is UiItem.Header -> {
                (holder as HeaderHolder).header.text = item.title
            }

            is UiItem.SubZone -> {
                val h = holder as SubZoneHolder
                h.title.text = item.title
                h.count.text = "${item.collectedCount}/${item.totalCount}"

                val context = h.itemView.context
                val resId = context.resources.getIdentifier(
                    item.imageUrl ?: "",
                    "drawable",
                    context.packageName
                )

                if (resId != 0)
                    h.img.setImageResource(resId)
                else
                    h.img.setImageResource(R.drawable.ic_launcher_foreground)

                // 🔥 클릭 이벤트 추가 (이게 없어서 지금 아무 반응 없는 거임)
                h.itemView.setOnClickListener {
                    onClick(item.id)   // ← MainActivity / SubZoneActivity에서 이 콜백으로 넘김
                }
            }


        }
    }
}
