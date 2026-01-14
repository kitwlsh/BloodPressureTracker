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

class BPAdapter(
    private val onDelete: (Long) -> Unit,
    private val onEdit: (BloodPressureRecord) -> Unit
) : ListAdapter<BloodPressureRecord, BPAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBpRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onDelete, onEdit)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemBpRecordBinding,
        private val onDelete: (Long) -> Unit,
        private val onEdit: (BloodPressureRecord) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun bind(record: BloodPressureRecord) {
            binding.tvDate.text = sdf.format(Date(record.timestamp))
            binding.tvSystolic.text = record.systolic.toString()
            binding.tvDiastolic.text = record.diastolic.toString()
            binding.tvPulse.text = record.pulse.toString()
            
            binding.btnDelete.setOnClickListener {
                onDelete(record.id)
            }

            // 항목 클릭 시 편집 기능 호출
            binding.root.setOnClickListener {
                onEdit(record)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<BloodPressureRecord>() {
        override fun areItemsTheSame(oldItem: BloodPressureRecord, newItem: BloodPressureRecord) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: BloodPressureRecord, newItem: BloodPressureRecord) = oldItem == newItem
    }
}
