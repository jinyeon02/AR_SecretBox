package com.example.treasurear.ui.treasure

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.treasurear.R
import com.example.treasurear.data.entity.TreasureEntity

class TreasureItemAdapter(
    private val items: List<TreasureEntity>
) : RecyclerView.Adapter<TreasureItemAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.itemImage)
        val name: TextView = view.findViewById(R.id.itemName)
        // count 뷰는 이제 사용 안 함
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_treasure, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.name.text = item.name
        val context = holder.itemView.context

        if (item.isCollected) {
            // ⭐ 수집된 보물 → JSON에 있는 이미지 사용
            val raw = item.imageUrl?.substringBeforeLast(".") ?: ""
            val resId = context.resources.getIdentifier(
                raw,
                "drawable",
                context.packageName
            )

            if (resId != 0) holder.img.setImageResource(resId)
            else holder.img.setImageResource(R.drawable.empty_chest) // fallback
        } else {
            // ⭐ 미수집 보물 → 빈 상자 이미지로 통일
            holder.img.setImageResource(R.drawable.empty_chest)
        }
    }



    override fun getItemCount(): Int = items.size
}
