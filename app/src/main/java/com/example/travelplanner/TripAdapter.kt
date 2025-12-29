package com.harukayuki.travelplanner

import android.graphics.Color
import android.util.Log
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView

class TripAdapter(
    private val onHeaderClick: (Trip) -> Unit,
    private val onHeaderLongClick: (Trip) -> Unit,
    private val onFavoriteClick: (Trip) -> Unit,
    private val onSegmentClick: (Segment, Boolean) -> Unit,
    private val onDeleteTrip: (Trip) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_HEADER = 0
    private val TYPE_ITEM = 1
    private var displayList = mutableListOf<Any>()
    private var segmentsDataMap = mapOf<Int, List<Segment>>()

    fun updateData(trips: List<Trip>, segmentsMap: Map<Int, List<Segment>>) {
        this.segmentsDataMap = segmentsMap
        displayList.clear()
        trips.forEach { trip ->
            displayList.add(trip)
            if (trip.isExpanded) {
                displayList.addAll(segmentsMap[trip.id] ?: emptyList())
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (displayList[position] is Trip) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(inflater.inflate(R.layout.item_trip_header, parent, false))
        else ItemVH(inflater.inflate(R.layout.item_segment, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val data = displayList[position]

        if (holder is HeaderVH && data is Trip) {
            holder.title.text = data.tripName
            val total = segmentsDataMap[data.id]?.sumOf { it.price.toDoubleOrNull() ?: 0.0 } ?: 0.0
            holder.cost.text = "预计: ¥${String.format("%.2f", total)}"

            // 星星点亮
            if (data.isFavorite) {
                holder.btnFav.setImageResource(android.R.drawable.btn_star_big_on)
                holder.btnFav.setColorFilter(Color.parseColor("#FFD600"))
            } else {
                holder.btnFav.setImageResource(android.R.drawable.btn_star_big_off)
                holder.btnFav.setColorFilter(Color.LTGRAY)
            }

            holder.itemView.setOnClickListener { onHeaderClick(data) }
            holder.itemView.setOnLongClickListener { onHeaderLongClick(data); true }
            holder.btnFav.setOnClickListener { onFavoriteClick(data) }
            holder.btnDel.setOnClickListener { onDeleteTrip(data) }
        }
        else if (holder is ItemVH && data is Segment) {
            holder.route.text = "${data.startLoc} ➔ ${data.endLoc}"
            holder.segPrice.text = "¥${data.price}"
            holder.detail.text = data.transport

            // --- 彻底修复：匹配逻辑 ---
            val transport = data.transport.trim()
            val iconRes = when (transport) {
                "汽车" -> R.drawable.ic_car
                "飞机" -> R.drawable.ic_plane
                "高铁" -> R.drawable.ic_railway
                "火车" -> R.drawable.ic_train
                "自行车" -> R.drawable.ic_bike
                "地铁" -> R.drawable.ic_metro
                else -> android.R.drawable.ic_menu_directions // 如果都没匹配上，显示自带指南针
            }

            // 设置图片，并确保它是可见的
            holder.ivIcon.setImageResource(iconRes)
            holder.ivIcon.visibility = View.VISIBLE
            // 暂时去掉 ColorFilter 以防干扰
            holder.ivIcon.clearColorFilter()

            val isLast = position == displayList.size - 1 || (position + 1 < displayList.size && displayList[position + 1] is Trip)
            holder.itemView.setOnClickListener { onSegmentClick(data, isLast) }
        }
    }

    override fun getItemCount() = displayList.size

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTripTitle)
        val cost: TextView = v.findViewById(R.id.tvTotalCost)
        val btnFav: ImageButton = v.findViewById(R.id.btnFavorite)
        val btnDel: ImageButton = v.findViewById(R.id.btnDeleteTrip)
    }

    class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
        val route: TextView = v.findViewById(R.id.tvRoute)
        val detail: TextView = v.findViewById(R.id.tvDetail)
        val segPrice: TextView = v.findViewById(R.id.tvSegPrice)
        val ivIcon: ImageView = v.findViewById(R.id.ivSegIcon)
        // 在新布局中此项已设为隐藏，保持代码不报错即可
        val timelineBottom: View = v.findViewById(R.id.timelineBottom)
    }
}