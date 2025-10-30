package com.example.anticenter.data

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.anticenter.SelectFeatures
import com.example.anticenter.database.AntiCenterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

open class AllowlistViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    // 使用懒加载: 第一次访问时才初始化,测试可以在访问前替换
    internal var repository: AntiCenterRepository = AntiCenterRepository.getInstance(application)
    
    // 允许测试中重写协程作用域
    protected open val coroutineScope: CoroutineScope get() = viewModelScope
    
    private val _allowlistItems = mutableStateOf<List<AllowlistItem>>(emptyList())
    val allowlistItems: State<List<AllowlistItem>> = _allowlistItems
    
    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading
    
    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage
    
    private val _operationSuccess = mutableStateOf<String?>(null)
    val operationSuccess: State<String?> = _operationSuccess
    
    /**
     * Load allowlist items for a specific feature type
     */
    fun loadAllowlistItems(featureType: SelectFeatures) {
        _isLoading.value = true
        _errorMessage.value = null
        
        coroutineScope.launch {
            try {
                val items = repository.getAllowlistItems(featureType)
                _allowlistItems.value = items
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load allowlist: ${e.message}"
                _allowlistItems.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Add a new allowlist item
     */
    fun addAllowlistItem(item: AllowlistItem, featureType: SelectFeatures) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                val result = repository.addAllowlistItem(item, featureType)
                
                if (result.isSuccess) {
                    _operationSuccess.value = "Item added successfully"
                    // Refresh the list
                    loadAllowlistItems(featureType)
                } else {
                    _errorMessage.value = "Failed to add item: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error adding item: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Update an existing allowlist item
     */
    fun updateAllowlistItem(item: AllowlistItem, featureType: SelectFeatures) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                val result = repository.updateAllowlistItem(item, featureType)
                
                if (result.isSuccess) {
                    _operationSuccess.value = "Item updated successfully"
                    // Refresh the list
                    loadAllowlistItems(featureType)
                } else {
                    _errorMessage.value = "Failed to update item: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error updating item: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Delete an allowlist item
     */
    fun deleteAllowlistItem(itemId: String, featureType: SelectFeatures) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                val result = repository.deleteAllowlistItem(itemId)
                
                if (result.isSuccess) {
                    _operationSuccess.value = "Item deleted successfully"
                    // Refresh the list
                    loadAllowlistItems(featureType)
                } else {
                    _errorMessage.value = "Failed to delete item: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting item: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Save multiple allowlist items (replace all for a feature)
     */
    fun saveAllowlistItems(items: List<AllowlistItem>, featureType: SelectFeatures) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                val result = repository.saveAllowlistItems(items, featureType)
                
                if (result.isSuccess) {
                    _operationSuccess.value = "Allowlist saved successfully"
                    _allowlistItems.value = items
                } else {
                    _errorMessage.value = "Failed to save allowlist: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error saving allowlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Check if a value exists in allowlist
     */
    fun checkValueInAllowlist(value: String, featureType: SelectFeatures, onResult: (Boolean) -> Unit) {
        coroutineScope.launch {
            try {
                val exists = repository.isValueInAllowlist(value, featureType)
                onResult(exists)
            } catch (e: Exception) {
                _errorMessage.value = "Error checking allowlist: ${e.message}"
                onResult(false)
            }
        }
    }
    
    /**
     * Refresh allowlist data
     */
    fun refreshData(featureType: SelectFeatures) {
        loadAllowlistItems(featureType)
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Clear success message
     */
    fun clearSuccess() {
        _operationSuccess.value = null
    }
    
    /**
     * Generate a unique ID for new allowlist items
     */
    fun generateItemId(): String {
        return System.currentTimeMillis().toString()
    }
    
    /**
     * Validate allowlist item before saving
     */
    fun validateItem(item: AllowlistItem, featureType: SelectFeatures): ValidationResult {
        return when {
            item.value.isBlank() -> ValidationResult.Error("Value cannot be empty")
            item.value.length > 200 -> ValidationResult.Error("Value too long (max 200 characters)")
            item.description.length > 500 -> ValidationResult.Error("Description too long (max 500 characters)")
            
            // Feature-specific validation
            featureType == SelectFeatures.callProtection && !isValidPhoneNumber(item.value) -> 
                ValidationResult.Error("Invalid phone number format")
            
            (featureType == SelectFeatures.emailProtection || featureType == SelectFeatures.emailBlacklist) && !isValidEmail(item.value) -> 
                ValidationResult.Error("Invalid email format")
            
            featureType == SelectFeatures.urlProtection && !isValidUrl(item.value) -> 
                ValidationResult.Error("Invalid URL format")
            
            else -> ValidationResult.Success
        }
    }
    
    /**
     * Simple phone number validation
     */
    private fun isValidPhoneNumber(phone: String): Boolean {
        val phoneRegex = Regex("^[+]?[0-9\\s\\-()]{7,15}$")
        return phoneRegex.matches(phone)
    }
    
    /**
     * Simple email validation
     */
    private fun isValidEmail(email: String): Boolean {
        val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        return emailRegex.matches(email)
    }
    
    /**
     * Simple URL validation
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ftp://")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear all allowlist items for a specific feature type
     */
    fun clearAllowlistByFeature(featureType: SelectFeatures) {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                val result = repository.saveAllowlistItems(emptyList(), featureType)
                
                if (result.isSuccess) {
                    _operationSuccess.value = "Allowlist cleared successfully"
                    _allowlistItems.value = emptyList()
                } else {
                    _errorMessage.value = "Failed to clear allowlist: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error clearing allowlist: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Clear all data (all features)
     */
    fun clearAllData() {
        coroutineScope.launch {
            try {
                _isLoading.value = true
                val result = repository.clearAllData()
                
                if (result.isSuccess) {
                    _operationSuccess.value = "All data cleared successfully"
                    _allowlistItems.value = emptyList()
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

/**
 * Validation result for allowlist items
 */
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}
