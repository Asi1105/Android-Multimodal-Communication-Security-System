package com.example.anticenter.database

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DatabaseInitializer is responsible for setting up the database on application startup
 * including optional insertion of initial data and validating table existence.
 * Note: DatabaseManager will automatically create the database when instantiated.
 */
object DatabaseInitializer {
    private const val TAG = "DatabaseInitializer"
    
    /**
     * Kick off database initialization. Should be called from Application.onCreate() or MainActivity.onCreate().
     * Primarily used for inserting initial data and verifying database integrity.
     */
    fun initialize(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Starting database verification...")
                
                // Obtain database instance (created in DatabaseManager init)
                val dbManager = DatabaseManager.getInstance(context)
                
                // Verify database creation and version
                val readableDb = dbManager.readableDatabase
                Log.d(TAG, "Database verified successfully, version: ${readableDb.version}")
                
                // Check if required tables exist
                checkTablesExist(dbManager)
                
                // Insert optional initial test data
                insertInitialData(dbManager)
                
                Log.d(TAG, "Database initialization verification complete")
            } catch (e: Exception) {
                Log.e(TAG, "Database verification failed", e)
            }
        }
    }

    /**
     * Verify that the allowlist and alert_log tables are present.
     */
    private fun checkTablesExist(dbManager: DatabaseManager) {
        val db = dbManager.readableDatabase
        
        // Check allowlist table
        val allowlistCursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='allowlist'",
            null
        )
        val allowlistExists = allowlistCursor.count > 0
        allowlistCursor.close()
        
        // Check alert_log table
        val alertLogCursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='alert_log'",
            null
        )
        val alertLogExists = alertLogCursor.count > 0
        alertLogCursor.close()
        
        Log.d(TAG, "Table existence status - allowlist: $allowlistExists, alert_log: $alertLogExists")
    }

    /**
     * Insert optional initial data (for testing or sample purposes).
     */
    private fun insertInitialData(dbManager: DatabaseManager) {
        // Here you can insert default allowlist entries or example alert logs
        
        Log.d(TAG, "Skipping initial data insertion (customize as needed)")
    }

    /**
     * Clear all data (useful for testing or resetting the database).
     */
    fun clearAllData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbManager = DatabaseManager.getInstance(context)
                dbManager.clearAllData()
                Log.d(TAG, "All data cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear data", e)
            }
        }
    }

    /**
     * Retrieve database info (for debugging purposes).
     * Returns file path, version, and item counts.
     */
    fun getDatabaseInfo(context: Context): String {
        return try {
            val dbManager = DatabaseManager.getInstance(context)
            val db = dbManager.readableDatabase
            
            val allowlistCount = dbManager.getAllowlistItems(com.example.anticenter.SelectFeatures.callProtection).size
            val alertLogCount = dbManager.getAlertLogItems(com.example.anticenter.SelectFeatures.callProtection).size
            
            """
            Database Information:
            - File path: ${db.path}
            - Version: ${db.version}
            - Allowlist item count: $allowlistCount
            - AlertLog item count: $alertLogCount
            """.trimIndent()
        } catch (e: Exception) {
            "Failed to get database information: ${e.message}"
        }
    }
}
