package com.example.anticenter.database

import android.content.Context
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.ContentLogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository layer for database operations
 * Provides a clean API for ViewModels to interact with the database
 */
class AntiCenterRepository private constructor(private val databaseManager: DatabaseManager) {
    
    companion object {
        @Volatile
        private var INSTANCE: AntiCenterRepository? = null
        
        fun getInstance(context: Context): AntiCenterRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AntiCenterRepository(
                    DatabaseManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }
    
    // ==================== ALLOWLIST OPERATIONS ====================
    
    /**
     * Get all allowlist items for a specific feature type
     */
    suspend fun getAllowlistItems(featureType: SelectFeatures): List<AllowlistItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.getAllowlistItems(featureType)
        }
    
    /**
     * Add a new allowlist item
     */
    suspend fun addAllowlistItem(item: AllowlistItem, featureType: SelectFeatures): Result<Long> = 
        withContext(Dispatchers.IO) {
            try {
                val id = databaseManager.insertAllowlistItem(item, featureType)
                if (id > 0) {
                    Result.success(id)
                } else {
                    Result.failure(Exception("Failed to insert allowlist item"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    /**
     * Update an existing allowlist item
     */
    suspend fun updateAllowlistItem(item: AllowlistItem, featureType: SelectFeatures): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                val rowsAffected = databaseManager.updateAllowlistItem(item, featureType)
                if (rowsAffected > 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("No rows updated"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    /**
     * Delete an allowlist item
     */
    suspend fun deleteAllowlistItem(itemId: String): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                val rowsAffected = databaseManager.deleteAllowlistItem(itemId)
                if (rowsAffected > 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("No rows deleted"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    /**
     * Check if a value is in the allowlist for a specific feature
     */
    suspend fun isValueInAllowlist(value: String, featureType: SelectFeatures): Boolean = 
        withContext(Dispatchers.IO) {
            databaseManager.isValueInAllowlist(value, featureType)
        }
    
    /**
     * Save multiple allowlist items (replace all for a feature)
     */
    suspend fun saveAllowlistItems(items: List<AllowlistItem>, featureType: SelectFeatures): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                // Delete all existing items for this feature
                databaseManager.deleteAllowlistItemsByFeature(featureType)
                
                // Insert new items
                items.forEach { item ->
                    databaseManager.insertAllowlistItem(item, featureType)
                }
                
                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    // ==================== ALERT LOG OPERATIONS ====================
    
    /**
     * Get all alert log items for a specific feature type
     */
    suspend fun getAlertLogItems(featureType: SelectFeatures): List<AlertLogItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.getAlertLogItems(featureType)
        }
    
    /**
     * Get alert log items with pagination
     */
    suspend fun getAlertLogItems(featureType: SelectFeatures, limit: Int, offset: Int): List<AlertLogItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.getAlertLogItems(featureType, limit, offset)
        }
    
    /**
     * Add a new alert log item
     */
    suspend fun addAlertLogItem(item: AlertLogItem, featureType: SelectFeatures): Result<Long> = 
        withContext(Dispatchers.IO) {
            try {
                val id = databaseManager.insertAlertLogItem(item, featureType)
                if (id > 0) {
                    Result.success(id)
                } else {
                    Result.failure(Exception("Failed to insert alert log item"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    /**
     * Get alert log count for a specific feature type
     */
    suspend fun getAlertLogCount(featureType: SelectFeatures): Int = 
        withContext(Dispatchers.IO) {
            databaseManager.getAlertLogCount(featureType)
        }
    
    /**
     * Clean up old alert logs (keep only recent N items)
     */
    suspend fun cleanupOldAlertLogs(featureType: SelectFeatures, keepCount: Int = 1000): Result<Int> = 
        withContext(Dispatchers.IO) {
            try {
                val deletedCount = databaseManager.cleanupOldAlertLogs(featureType, keepCount)
                Result.success(deletedCount)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    // ==================== CONTENT LOG OPERATIONS ====================

    /**
     * Add a new content log item
     */
    suspend fun addContentLogItem(item: ContentLogItem): Result<Long> = 
        withContext(Dispatchers.IO) {
            try {
                val id = databaseManager.insertContentLogItem(item)
                if (id > 0) {
                    Result.success(id)
                } else {
                    Result.failure(Exception("Failed to insert content log item"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Get all content log items for a specific type
     */
    suspend fun getContentLogItems(type: String): List<ContentLogItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.getContentLogItems(type)
        }

    /**
     * Get all content log items (all types)
     */
    suspend fun getAllContentLogItems(): List<ContentLogItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.getAllContentLogItems()
        }

    /**
     * Get content log items with pagination
     */
    suspend fun getContentLogItems(type: String, limit: Int, offset: Int): List<ContentLogItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.getContentLogItems(type, limit, offset)
        }

    /**
     * Get content log count for a specific type
     */
    suspend fun getContentLogCount(type: String): Int = 
        withContext(Dispatchers.IO) {
            databaseManager.getContentLogCount(type)
        }

    /**
     * Get total content log count (all types)
     */
    suspend fun getTotalContentLogCount(): Int = 
        withContext(Dispatchers.IO) {
            databaseManager.getTotalContentLogCount()
        }

    /**
     * Delete content log item by ID
     */
    suspend fun deleteContentLogItem(id: Long): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                val rowsAffected = databaseManager.deleteContentLogItem(id)
                if (rowsAffected > 0) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("No rows deleted"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Delete all content log items for a specific type
     */
    suspend fun deleteContentLogItemsByType(type: String): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                val rowsAffected = databaseManager.deleteContentLogItemsByType(type)
                Result.success(rowsAffected > 0)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Clean up old content logs (keep only recent N items per type)
     */
    suspend fun cleanupOldContentLogs(type: String, keepCount: Int = 1000): Result<Int> = 
        withContext(Dispatchers.IO) {
            try {
                val deletedCount = databaseManager.cleanupOldContentLogs(type, keepCount)
                Result.success(deletedCount)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Search content log items by content text
     */
    suspend fun searchContentLogItems(query: String, type: String? = null): List<ContentLogItem> = 
        withContext(Dispatchers.IO) {
            databaseManager.searchContentLogItems(query, type)
        }

    /**
     * Get content log items for CSV export (with all data)
     * This method is specifically designed for export purposes
     */
    suspend fun getContentLogItemsForExport(type: String): List<ContentLogItem> = 
        withContext(Dispatchers.IO) {
            try {
                val items = databaseManager.getContentLogItems(type)
                android.util.Log.d("AntiCenterRepository", "Retrieved ${items.size} items for export, type: $type")
                items
            } catch (e: Exception) {
                android.util.Log.e("AntiCenterRepository", "Failed to get content log items for export", e)
                emptyList()
            }
        }

    /**
     * Get all content log items for CSV export (all types)
     */
    suspend fun getAllContentLogItemsForExport(): List<ContentLogItem> = 
        withContext(Dispatchers.IO) {
            try {
                val items = databaseManager.getAllContentLogItems()
                android.util.Log.d("AntiCenterRepository", "Retrieved ${items.size} total items for export")
                items
            } catch (e: Exception) {
                android.util.Log.e("AntiCenterRepository", "Failed to get all content log items for export", e)
                emptyList()
            }
        }

    // ==================== UTILITY OPERATIONS ====================

    /**
     * Get database statistics
     */
    suspend fun getDatabaseStats(): DatabaseStats = 
        withContext(Dispatchers.IO) {
            databaseManager.getDatabaseStats()
        }
    
    /**
     * Clear all data (useful for testing or reset)
     */
    suspend fun clearAllData(): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                databaseManager.clearAllData()
                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    /**
     * Initialize database with sample data (for testing/demo purposes)
     */
    suspend fun initializeSampleData(): Result<Boolean> = 
        withContext(Dispatchers.IO) {
            try {
                // Sample allowlist items
                val callAllowlist = listOf(
                    AllowlistItem("1", "Mom", "Phone", "+1-555-0001", "Family contact"),
                    AllowlistItem("2", "Work", "Phone", "+1-555-0002", "Office number"),
                    AllowlistItem("3", "Doctor", "Phone", "+1-555-0003", "Medical contact")
                )
                
                val emailAllowlist = listOf(
                    AllowlistItem("4", "Work Email", "Email", "work@company.com", "Company email"),
                    AllowlistItem("5", "Bank", "Email", "noreply@bank.com", "Bank notifications"),
                    AllowlistItem("6", "Friend", "Email", "friend@example.com", "Personal contact")
                )
                
                val urlAllowlist = listOf(
                    AllowlistItem("7", "Company Site", "URL", "https://company.com", "Work website"),
                    AllowlistItem("8", "Bank Portal", "URL", "https://bank.com", "Banking portal"),
                    AllowlistItem("9", "News Site", "URL", "https://news.com", "Trusted news")
                )
                
                // Save allowlist items
                saveAllowlistItems(callAllowlist, SelectFeatures.callProtection)
                saveAllowlistItems(emailAllowlist, SelectFeatures.emailProtection)
                saveAllowlistItems(urlAllowlist, SelectFeatures.urlProtection)
                
                // Sample alert log items
                val sampleAlertLogs = mapOf(
                    SelectFeatures.callProtection to listOf(
                        AlertLogItem("2025-01-15 10:30", "Suspicious Call", "+1-555-0123", "Blocked"),
                        AlertLogItem("2025-01-14 14:22", "Spam Call", "+1-555-0456", "Warning"),
                        AlertLogItem("2025-01-13 09:15", "Scam Call", "+1-555-0789", "Blocked")
                    ),
                    SelectFeatures.emailProtection to listOf(
                        AlertLogItem("2025-01-15 08:30", "Phishing Email", "fake@scammer.com", "Blocked"),
                        AlertLogItem("2025-01-14 12:15", "Spam Email", "spam@example.com", "Warning"),
                        AlertLogItem("2025-01-13 17:45", "Malware Email", "virus@malware.net", "Blocked")
                    ),
                    SelectFeatures.urlProtection to listOf(
                        AlertLogItem("2025-01-15 13:45", "Malicious URL", "https://fake-bank.com", "Blocked"),
                        AlertLogItem("2025-01-14 10:20", "Phishing URL", "https://scam-site.net", "Warning"),
                        AlertLogItem("2025-01-13 15:30", "Malware URL", "https://virus-download.com", "Blocked")
                    ),
                    SelectFeatures.meetingProtection to listOf(
                        AlertLogItem("2025-01-15 11:00", "Suspicious Meeting", "zoom://meeting/123", "Warning"),
                        AlertLogItem("2025-01-14 16:30", "Phishing Meeting", "teams://meeting/456", "Blocked"),
                        AlertLogItem("2025-01-13 13:15", "Fake Meeting", "meet.google.com/abc", "Blocked")
                    )
                )
                
                // Save alert log items
                sampleAlertLogs.forEach { (feature, logs) ->
                    logs.forEach { log ->
                        addAlertLogItem(log, feature)
                    }
                }
                
                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
