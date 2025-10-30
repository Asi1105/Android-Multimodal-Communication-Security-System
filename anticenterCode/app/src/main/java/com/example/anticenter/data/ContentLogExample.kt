package com.example.anticenter.data

import android.content.Context
import com.example.anticenter.database.AntiCenterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Content log utility for demonstration and testing
 * Shows how to use the new content log functionality
 */
class ContentLogExample(private val context: Context) {
    
    private val repository = AntiCenterRepository.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Add sample content log items for testing
     */
    fun addSampleContentLogs() {
        scope.launch {
            // Sample email content
            val emailContent = ContentLogItem(
                type = "Email",
                timestamp = System.currentTimeMillis(),
                content = "Subject: Urgent Account Verification Required\n" +
                         "From: security@fake-bank.com\n" +
                         "Dear Customer, Your account has been suspended. " +
                         "Click here to verify: http://fake-bank-verify.scam.com"
            )
            
            // Sample Zoom content
            val zoomContent = ContentLogItem(
                type = "Zoom",
                timestamp = System.currentTimeMillis() - 3600000, // 1 hour ago
                content = "Meeting ID: 123-456-789\n" +
                         "Host: suspicious-host@scammer.com\n" +
                         "Topic: Important Bank Account Information\n" +
                         "Join URL: https://fake-zoom.scam.com/j/123456789"
            )
            
            // Sample phone call content
            val phoneContent = ContentLogItem(
                type = "PhoneCall",
                timestamp = System.currentTimeMillis() - 7200000, // 2 hours ago
                content = "Caller: +1-555-SCAM-123\n" +
                         "Duration: 5:30\n" +
                         "Transcript: Hello, this is from your bank. " +
                         "We need to verify your credit card information immediately..."
            )
            
            // Add content logs
            val results = listOf(
                repository.addContentLogItem(emailContent),
                repository.addContentLogItem(zoomContent),
                repository.addContentLogItem(phoneContent)
            )
            
            results.forEachIndexed { index, result ->
                val type = listOf("Email", "Zoom", "PhoneCall")[index]
                if (result.isSuccess) {
                    android.util.Log.d("ContentLogExample", "Sample $type content added with ID: ${result.getOrNull()}")
                } else {
                    android.util.Log.e("ContentLogExample", "Failed to add $type content: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }
    
    /**
     * Retrieve and display content logs by type
     */
    fun displayContentLogsByType(type: String) {
        scope.launch {
            val items = repository.getContentLogItems(type)
            android.util.Log.d("ContentLogExample", "Found ${items.size} content log items for type: $type")
            
            items.forEach { item ->
                android.util.Log.d("ContentLogExample", 
                    "ID: ${item.id}, Type: ${item.type}, Time: ${item.getFormattedTime()}\n" +
                    "Content: ${item.content.take(100)}${if (item.content.length > 100) "..." else ""}")
            }
        }
    }
    
    /**
     * Display all content logs
     */
    fun displayAllContentLogs() {
        scope.launch {
            val items = repository.getAllContentLogItems()
            android.util.Log.d("ContentLogExample", "Found ${items.size} total content log items")
            
            items.groupBy { it.type }.forEach { (type, typeItems) ->
                android.util.Log.d("ContentLogExample", "$type: ${typeItems.size} items")
                typeItems.take(3).forEach { item -> // Show first 3 items per type
                    android.util.Log.d("ContentLogExample", 
                        "  - ${item.getFormattedTime()}: ${item.content.take(50)}...")
                }
            }
        }
    }
    
    /**
     * Search content logs by keyword
     */
    fun searchContentLogs(keyword: String, type: String? = null) {
        scope.launch {
            val items = repository.searchContentLogItems(keyword, type)
            val typeFilter = type?.let { " (type: $it)" } ?: ""
            android.util.Log.d("ContentLogExample", "Found ${items.size} content log items matching '$keyword'$typeFilter")
            
            items.forEach { item ->
                android.util.Log.d("ContentLogExample", 
                    "Match - Type: ${item.type}, Time: ${item.getFormattedTime()}\n" +
                    "Content: ${item.content}")
            }
        }
    }
    
    /**
     * Get content log statistics
     */
    fun getContentLogStats() {
        scope.launch {
            val stats = repository.getDatabaseStats()
            
            android.util.Log.d("ContentLogExample", "=== Content Log Statistics ===")
            android.util.Log.d("ContentLogExample", "Total content log items: ${stats.totalContentLogItems}")
            
            stats.contentLogCounts.forEach { (type, count) ->
                android.util.Log.d("ContentLogExample", "$type: $count items")
            }
        }
    }
    
    /**
     * Clean up old content logs
     */
    fun cleanupOldContentLogs(type: String, keepCount: Int = 10) {
        scope.launch {
            val result = repository.cleanupOldContentLogs(type, keepCount)
            if (result.isSuccess) {
                android.util.Log.d("ContentLogExample", "Cleaned up ${result.getOrNull()} old $type content log items")
            } else {
                android.util.Log.e("ContentLogExample", "Failed to cleanup $type content logs: ${result.exceptionOrNull()?.message}")
            }
        }
    }
}