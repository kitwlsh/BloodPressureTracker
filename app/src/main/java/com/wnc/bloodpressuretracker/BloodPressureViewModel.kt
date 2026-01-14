package com.wnc.bloodpressuretracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class BloodPressureViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).bpDao()
    val allRecords: LiveData<List<BloodPressureRecord>> = dao.getAllRecords()

    fun insert(record: BloodPressureRecord) = viewModelScope.launch {
        dao.insert(record)
    }

    fun update(record: BloodPressureRecord) = viewModelScope.launch {
        dao.insert(record)
    }

    fun delete(id: Long) = viewModelScope.launch {
        dao.delete(id)
    }

    fun deleteAll() = viewModelScope.launch {
        dao.deleteAll()
    }
}
