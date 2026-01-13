package com.wnc.bloodpressuretracker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wnc.bloodpressuretracker.databinding.ItemBpRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BPAdapter(private val onDelete: (Long) -> Unit) : ListAdapter<BloodPressureRecord, BPAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBpRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemBpRecordBinding, private val onDelete: (Long) -> Unit) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun bind(record: BloodPressureRecord) {
            binding.tvDate.text = sdf.format(Date(record.timestamp))
            binding.tvSystolic.text = "SYS: ${record.systolic}"
            binding.tvDiastolic.text = "DIA: ${record.diastolic}"
            binding.tvPulse.text = "PULSE: ${record.pulse}"
            
            binding.btnDelete.setOnClickListener {
                onDelete(record.id)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<BloodPressureRecord>() {
        override fun areItemsTheSame(oldItem: BloodPressureRecord, newItem: BloodPressureRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: BloodPressureRecord, newItem: BloodPressureRecord) = oldItem == newItem
    }
}
