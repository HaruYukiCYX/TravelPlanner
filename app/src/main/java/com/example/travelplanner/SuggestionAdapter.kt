package com.harukayuki.travelplanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.services.help.Tip

class SuggestionAdapter(private val onSelected: (Tip) -> Unit) : RecyclerView.Adapter<SuggestionAdapter.VH>() {
    private var tips = mutableListOf<Tip>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvTipName)
        val addr: TextView = v.findViewById(R.id.tvTipAddress)
    }

    fun setData(newList: List<Tip>) {
        tips.clear()
        tips.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tip = tips[position]
        holder.name.text = tip.name
        holder.addr.text = tip.address ?: "暂无详细地址"
        holder.itemView.setOnClickListener { onSelected(tip) }
    }

    override fun getItemCount() = tips.size
}