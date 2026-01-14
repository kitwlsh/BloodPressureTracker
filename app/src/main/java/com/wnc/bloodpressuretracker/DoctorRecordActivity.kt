package com.wnc.bloodpressuretracker

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wnc.bloodpressuretracker.databinding.ActivityDoctorRecordBinding
import com.wnc.bloodpressuretracker.databinding.ItemDoctorRowBinding
import java.text.SimpleDateFormat
import java.util.*

class DoctorRecordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDoctorRecordBinding
    private val viewModel: BloodPressureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDoctorRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = DoctorTableAdapter()
        binding.rvDoctorTable.layoutManager = LinearLayoutManager(this)
        binding.rvDoctorTable.adapter = adapter

        viewModel.allRecords.observe(this) { records ->
            val groupedByDate = records.groupBy {
                val cal = Calendar.getInstance()
                cal.timeInMillis = it.timestamp
                String.format("%02d월 %02d일", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            }
            adapter.submitData(groupedByDate)
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    class DoctorTableAdapter : RecyclerView.Adapter<DoctorTableAdapter.ViewHolder>() {
        private var dateList = listOf<String>()
        private var dataMap = mapOf<String, List<BloodPressureRecord>>()

        fun submitData(map: Map<String, List<BloodPressureRecord>>) {
            dataMap = map
            dateList = map.keys.toList().sortedDescending()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDoctorRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val date = dateList[position]
            val records = dataMap[date] ?: emptyList()
            holder.bind(date, records.sortedBy { it.timestamp })
        }

        override fun getItemCount() = dateList.size

        class ViewHolder(private val binding: ItemDoctorRowBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(date: String, records: List<BloodPressureRecord>) {
                binding.tvTableDate.text = date
                binding.containerRecords.removeAllViews()

                // 시간대별 그룹화
                val morning = records.filter { isHourInRange(it.timestamp, 5, 10) }
                val afternoon = records.filter { isHourInRange(it.timestamp, 11, 16) }
                val evening = records.filter { isHourInRange(it.timestamp, 17, 21) }
                val night = records.filter { !isHourInRange(it.timestamp, 5, 21) }

                addCell(morning)
                addCell(afternoon)
                addCell(evening)
                addCell(night)
            }

            private fun isHourInRange(timestamp: Long, start: Int, end: Int): Boolean {
                val cal = Calendar.getInstance()
                cal.timeInMillis = timestamp
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                return hour in start..end
            }

            private fun addCell(cellRecords: List<BloodPressureRecord>) {
                val context = binding.root.context
                val inflater = LayoutInflater.from(context)
                
                // 해당 시간대(열)를 위한 수직 컨테이너 생성
                val slotLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        (100 * context.resources.displayMetrics.density).toInt(),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                if (cellRecords.isEmpty()) {
                    val emptyView = inflater.inflate(R.layout.item_doctor_cell, slotLayout, false)
                    emptyView.findViewById<TextView>(R.id.tvTime).text = "-"
                    emptyView.findViewById<TextView>(R.id.tvBp).text = "- / -"
                    emptyView.findViewById<TextView>(R.id.tvPulse).text = "-"
                    slotLayout.addView(emptyView)
                } else {
                    val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                    for (record in cellRecords) {
                        // 각 기록마다 새로운 셀 레이아웃 인플레이트 (시간/혈압/맥박 세트)
                        val recordView = inflater.inflate(R.layout.item_doctor_cell, slotLayout, false)
                        recordView.findViewById<TextView>(R.id.tvTime).text = timeSdf.format(Date(record.timestamp))
                        recordView.findViewById<TextView>(R.id.tvBp).text = "${record.systolic}/${record.diastolic}"
                        recordView.findViewById<TextView>(R.id.tvPulse).text = "♥ ${record.pulse}"
                        
                        slotLayout.addView(recordView)

                        // 같은 칸 내에 여러 기록이 있을 경우 구분선 추가
                        if (record != cellRecords.last()) {
                            val divider = View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                                setBackgroundColor(Color.parseColor("#7AB040")) // 약간 짙은 초록색 구분선
                            }
                            slotLayout.addView(divider)
                        }
                    }
                }
                binding.containerRecords.addView(slotLayout)
            }
        }
    }
}
