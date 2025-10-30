package com.example.anticenter.data

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.anticenter.SelectFeatures
import com.example.anticenter.database.AntiCenterRepository
import kotlinx.coroutines.launch

class AlertLogViewModel(application: Application) : AndroidViewModel(application) {
    
    internal var repository: AntiCenterRepository = AntiCenterRepository.getInstance(application)
    
    private val _alertLogs = mutableStateOf<List<AlertLogItem>>(emptyList())
    val alertLogs: State<List<AlertLogItem>> = _alertLogs
    
    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading
    
    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage
    
    /**
     * Load alert logs based on feature type from database
     */
    fun loadAlertLogs(featureType: SelectFeatures) {
        _isLoading.value = true
        _errorMessage.value = null
        
        viewModelScope.launch {
            try {
                val logs = repository.getAlertLogItems(featureType)
                _alertLogs.value = logs
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load alert logs: ${e.message}"
                _alertLogs.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Add a new alert log item
     */
    fun addAlertLog(item: AlertLogItem, featureType: SelectFeatures) {
        viewModelScope.launch {
            try {
                val result = repository.addAlertLogItem(item, featureType)
                if (result.isSuccess) {
                    // Refresh the list
                    loadAlertLogs(featureType)
                } else {
                    _errorMessage.value = "Failed to add alert log"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error adding alert log: ${e.message}"
            }
        }
    }
    
    /**
     * Refresh data from database
     */
    fun refreshData(featureType: SelectFeatures) {
        loadAlertLogs(featureType)
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Initialize sample data (for demo purposes)
     */
    fun initializeSampleData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val result = repository.initializeSampleData()
                if (result.isFailure) {
                    _errorMessage.value = "Failed to initialize sample data: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error initializing data: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Get alert log count for a feature
     */
    fun getAlertLogCount(featureType: SelectFeatures, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val count = repository.getAlertLogCount(featureType)
                onResult(count)
            } catch (e: Exception) {
                _errorMessage.value = "Error getting count: ${e.message}"
                onResult(0)
            }
        }
    }
    
    /**
     * Clean up old alert logs
     */
    fun cleanupOldLogs(featureType: SelectFeatures, keepCount: Int = 1000) {
        viewModelScope.launch {
            try {
                repository.cleanupOldAlertLogs(featureType, keepCount)
                // Refresh data after cleanup
                loadAlertLogs(featureType)
            } catch (e: Exception) {
                _errorMessage.value = "Error cleaning up logs: ${e.message}"
            }
        }
    }
    
    /**
     * Clear all alert logs for a specific feature
     */
    fun clearAlertLogsByFeature(featureType: SelectFeatures) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                // Clean up keeping 0 items = delete all
                val result = repository.cleanupOldAlertLogs(featureType, 0)
                
                if (result.isSuccess) {
                    _alertLogs.value = emptyList()
                } else {
                    _errorMessage.value = "Failed to clear alert logs: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error clearing alert logs: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Clear all data (all features) 
     */
    fun clearAllData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val result = repository.clearAllData()
                
                if (result.isSuccess) {
                    _alertLogs.value = emptyList()
                } else {
                    _errorMessage.value = "Failed to clear all data: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error clearing all data: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}