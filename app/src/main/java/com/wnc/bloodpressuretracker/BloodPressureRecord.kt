package com.wnc.bloodpressuretracker

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "bp_records")
data class BloodPressureRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val timestamp: Long = System.currentTimeMillis()
)
