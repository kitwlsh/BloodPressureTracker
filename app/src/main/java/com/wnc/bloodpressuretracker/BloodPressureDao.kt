package com.wnc.bloodpressuretracker

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BloodPressureDao {
    @Query("SELECT * FROM bp_records ORDER BY timestamp DESC")
    fun getAllRecords(): LiveData<List<BloodPressureRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BloodPressureRecord)

    @Query("DELETE FROM bp_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bp_records")
    suspend fun deleteAll()
}
