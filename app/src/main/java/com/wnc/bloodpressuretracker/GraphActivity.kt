package com.wnc.bloodpressuretracker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.wnc.bloodpressuretracker.databinding.ActivityGraphBinding
import java.text.SimpleDateFormat
import java.util.*

class GraphActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGraphBinding
    private val viewModel: BloodPressureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChart()

        viewModel.allRecords.observe(this) { records ->
            if (records.isNotEmpty()) {
                updateChartData(records.reversed()) // 시간 순으로 정렬
            }
        }
    }

    private fun setupChart() {
        binding.lineChart.apply {
            description.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.valueFormatter = object : ValueFormatter() {
                private val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
                override fun getFormattedValue(value: Float): String {
                    return sdf.format(Date(value.toLong()))
                }
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
        }
    }

    private fun updateChartData(records: List<BloodPressureRecord>) {
        val sysEntries = records.map { Entry(it.timestamp.toFloat(), it.systolic.toFloat()) }
        val diaEntries = records.map { Entry(it.timestamp.toFloat(), it.diastolic.toFloat()) }

        val sysDataSet = LineDataSet(sysEntries, "수축기(SYS)").apply {
            color = Color.RED
            setCircleColor(Color.RED)
            lineWidth = 2f
        }

        val diaDataSet = LineDataSet(diaEntries, "이완기(DIA)").apply {
            color = Color.BLUE
            setCircleColor(Color.BLUE)
            lineWidth = 2f
        }

        binding.lineChart.data = LineData(sysDataSet, diaDataSet)
        binding.lineChart.invalidate() // 차트 갱신
    }
}
