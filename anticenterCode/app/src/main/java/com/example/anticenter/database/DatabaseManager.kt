package com.example.anticenter.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.ContentLogItem

/**
 * DatabaseManager handles creation, upgrade and CRUD operations
 * for the allowlist and alert_log tables using the SQLite database.
 */
class DatabaseManager private constructor(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    
    init {
        // Immediately create or open the database when constructing
        try {
            val db = this.writableDatabase
            if (db != null) {
                android.util.Log.d("DatabaseManager", "Database created/opened at: ${db.path}")
                // Connection is managed by SQLiteOpenHelper, do not close explicitly
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to create/open database", e)
            throw e
        }
    }
    
    companion object {
        private const val DATABASE_NAME = "anticenter.db"
        private const val DATABASE_VERSION = 2 // Updated to version 2 for content table
        
        // Allowlist Table
        private const val TABLE_ALLOWLIST = "allowlist"
        private const val ALLOWLIST_ID = "id"
        private const val ALLOWLIST_NAME = "name"
        private const val ALLOWLIST_TYPE = "type"
        private const val ALLOWLIST_VALUE = "value"
        private const val ALLOWLIST_DESCRIPTION = "description"
        private const val ALLOWLIST_FEATURE_TYPE = "feature_type"
        private const val ALLOWLIST_CREATED_AT = "created_at"
        
        // AlertLog Table
        private const val TABLE_ALERT_LOG = "alert_log"
        private const val ALERT_LOG_ID = "id"
        private const val ALERT_LOG_TIME = "time"
        private const val ALERT_LOG_TYPE = "type"
        private const val ALERT_LOG_SOURCE = "source"
        private const val ALERT_LOG_STATUS = "status"
        private const val ALERT_LOG_FEATURE_TYPE = "feature_type"
        private const val ALERT_LOG_CREATED_AT = "created_at"
        
        // ContentLog Table
        private const val TABLE_CONTENT_LOG = "content_log"
        private const val CONTENT_LOG_ID = "id"
        private const val CONTENT_LOG_TYPE = "type"
        private const val CONTENT_LOG_TIMESTAMP = "timestamp"
        private const val CONTENT_LOG_CONTENT = "content"
        private const val CONTENT_LOG_CREATED_AT = "created_at"
        
        @Volatile
        private var INSTANCE: DatabaseManager? = null
        
        /**
         * Get singleton instance of DatabaseManager.
         * The database file will be created on first call.
         */
        fun getInstance(context: Context): DatabaseManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseManager(context.applicationContext).also { 
                    INSTANCE = it 
                    android.util.Log.d("DatabaseManager", "Singleton instance created")
                }
            }
        }
        
        /**
         * Initialize the DatabaseManager.
         * Should be called from Application.onCreate() or MainActivity.onCreate().
         */
        fun initialize(context: Context) {
            android.util.Log.d("DatabaseManager", "Initializing DatabaseManager...")
            getInstance(context)
            android.util.Log.d("DatabaseManager", "DatabaseManager initialization complete")
        }
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        // Create Allowlist table
        val createAllowlistTable = """
            CREATE TABLE $TABLE_ALLOWLIST (
                $ALLOWLIST_ID TEXT PRIMARY KEY,
                $ALLOWLIST_NAME TEXT NOT NULL,
                $ALLOWLIST_TYPE TEXT NOT NULL,
                $ALLOWLIST_VALUE TEXT NOT NULL,
                $ALLOWLIST_DESCRIPTION TEXT,
                $ALLOWLIST_FEATURE_TYPE TEXT NOT NULL,
                $ALLOWLIST_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        
        // Create AlertLog table
        val createAlertLogTable = """
            CREATE TABLE $TABLE_ALERT_LOG (
                $ALERT_LOG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $ALERT_LOG_TIME TEXT NOT NULL,
                $ALERT_LOG_TYPE TEXT NOT NULL,
                $ALERT_LOG_SOURCE TEXT NOT NULL,
                $ALERT_LOG_STATUS TEXT NOT NULL,
                $ALERT_LOG_FEATURE_TYPE TEXT NOT NULL,
                $ALERT_LOG_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        // Create ContentLog table
        val createContentLogTable = """
            CREATE TABLE $TABLE_CONTENT_LOG (
                $CONTENT_LOG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $CONTENT_LOG_TYPE TEXT NOT NULL,
                $CONTENT_LOG_TIMESTAMP INTEGER NOT NULL,
                $CONTENT_LOG_CONTENT TEXT NOT NULL,
                $CONTENT_LOG_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        db.execSQL(createAllowlistTable)
        db.execSQL(createAlertLogTable)
        db.execSQL(createContentLogTable)

        // Create indexes for better performance
        db.execSQL("CREATE INDEX idx_allowlist_feature_type ON $TABLE_ALLOWLIST($ALLOWLIST_FEATURE_TYPE)")
        db.execSQL("CREATE INDEX idx_alert_log_feature_type ON $TABLE_ALERT_LOG($ALERT_LOG_FEATURE_TYPE)")
        db.execSQL("CREATE INDEX idx_content_log_type ON $TABLE_CONTENT_LOG($CONTENT_LOG_TYPE)")
        db.execSQL("CREATE INDEX idx_content_log_timestamp ON $TABLE_CONTENT_LOG($CONTENT_LOG_TIMESTAMP)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Upgrade database schema based on version
        when (oldVersion) {
            1 -> {
                // Upgrade from version 1 to 2: Add content_log table
                val createContentLogTable = """
                    CREATE TABLE $TABLE_CONTENT_LOG (
                        $CONTENT_LOG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $CONTENT_LOG_TYPE TEXT NOT NULL,
                        $CONTENT_LOG_TIMESTAMP INTEGER NOT NULL,
                        $CONTENT_LOG_CONTENT TEXT NOT NULL,
                        $CONTENT_LOG_CREATED_AT INTEGER NOT NULL
                    )
                """.trimIndent()
                
                db.execSQL(createContentLogTable)
                db.execSQL("CREATE INDEX idx_content_log_type ON $TABLE_CONTENT_LOG($CONTENT_LOG_TYPE)")
                db.execSQL("CREATE INDEX idx_content_log_timestamp ON $TABLE_CONTENT_LOG($CONTENT_LOG_TIMESTAMP)")
                
                android.util.Log.d("DatabaseManager", "Upgraded database from version $oldVersion to $newVersion - Added content_log table")
            }
            else -> {
                // For other version changes, recreate all tables
                db.execSQL("DROP TABLE IF EXISTS $TABLE_ALLOWLIST")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_ALERT_LOG")
                db.execSQL("DROP TABLE IF EXISTS $TABLE_CONTENT_LOG")
                onCreate(db)
                android.util.Log.d("DatabaseManager", "Database recreated for version upgrade from $oldVersion to $newVersion")
            }
        }
    }
    
    // ==================== ALLOWLIST OPERATIONS ====================
    
    /**
     * Insert or replace an allowlist item for the given feature type.
     */
    fun insertAllowlistItem(item: AllowlistItem, featureType: SelectFeatures): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(ALLOWLIST_ID, item.id)
            put(ALLOWLIST_NAME, item.name)
            put(ALLOWLIST_TYPE, item.type)
            put(ALLOWLIST_VALUE, item.value)
            put(ALLOWLIST_DESCRIPTION, item.description)
            put(ALLOWLIST_FEATURE_TYPE, featureType.name)
            put(ALLOWLIST_CREATED_AT, System.currentTimeMillis())
        }
        
        return try {
            db.insertWithOnConflict(TABLE_ALLOWLIST, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * Query all allowlist items for the given feature type.
     */
    fun getAllowlistItems(featureType: SelectFeatures): List<AllowlistItem> {
        val items = mutableListOf<AllowlistItem>()
        val db = this.readableDatabase
        
        val cursor = db.query(
            TABLE_ALLOWLIST,
            null,
            "$ALLOWLIST_FEATURE_TYPE = ?",
            arrayOf(featureType.name),
            null,
            null,
            "$ALLOWLIST_CREATED_AT DESC"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = AllowlistItem(
                    id = c.getString(c.getColumnIndexOrThrow(ALLOWLIST_ID)),
                    name = c.getString(c.getColumnIndexOrThrow(ALLOWLIST_NAME)),
                    type = c.getString(c.getColumnIndexOrThrow(ALLOWLIST_TYPE)),
                    value = c.getString(c.getColumnIndexOrThrow(ALLOWLIST_VALUE)),
                    description = c.getString(c.getColumnIndexOrThrow(ALLOWLIST_DESCRIPTION)) ?: ""
                )
                items.add(item)
            }
        }
        
        return items
    }
    
    /**
     * Update an existing allowlist item.
     */
    fun updateAllowlistItem(item: AllowlistItem, featureType: SelectFeatures): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(ALLOWLIST_NAME, item.name)
            put(ALLOWLIST_TYPE, item.type)
            put(ALLOWLIST_VALUE, item.value)
            put(ALLOWLIST_DESCRIPTION, item.description)
            put(ALLOWLIST_FEATURE_TYPE, featureType.name)
        }
        
        return db.update(
            TABLE_ALLOWLIST,
            values,
            "$ALLOWLIST_ID = ?",
            arrayOf(item.id)
        )
    }
    
    /**
     * Delete allowlist item
     */
    fun deleteAllowlistItem(itemId: String): Int {
        val db = this.writableDatabase
        return db.delete(
            TABLE_ALLOWLIST,
            "$ALLOWLIST_ID = ?",
            arrayOf(itemId)
        )
    }
    
    /**
     * Delete all allowlist items for a specific feature type
     */
    fun deleteAllowlistItemsByFeature(featureType: SelectFeatures): Int {
        val db = this.writableDatabase
        return db.delete(
            TABLE_ALLOWLIST,
            "$ALLOWLIST_FEATURE_TYPE = ?",
            arrayOf(featureType.name)
        )
    }
    
    /**
     * Check if a value exists in allowlist for a specific feature
     */
    fun isValueInAllowlist(value: String, featureType: SelectFeatures): Boolean {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_ALLOWLIST,
            arrayOf(ALLOWLIST_ID),
            "$ALLOWLIST_VALUE = ? AND $ALLOWLIST_FEATURE_TYPE = ?",
            arrayOf(value, featureType.name),
            null,
            null,
            null,
            "1"
        )
        
        val exists = cursor?.use { it.count > 0 } ?: false
        return exists
    }
    
    // ==================== ALERT LOG OPERATIONS ====================
    
    /**
     * Insert alert log item
     */
    fun insertAlertLogItem(item: AlertLogItem, featureType: SelectFeatures): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(ALERT_LOG_TIME, item.time)
            put(ALERT_LOG_TYPE, item.type)
            put(ALERT_LOG_SOURCE, item.source)
            put(ALERT_LOG_STATUS, item.status)
            put(ALERT_LOG_FEATURE_TYPE, featureType.name)
            put(ALERT_LOG_CREATED_AT, System.currentTimeMillis())
        }
        
        return try {
            db.insert(TABLE_ALERT_LOG, null, values)
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * Get all alert log items for a specific feature type
     */
    fun getAlertLogItems(featureType: SelectFeatures): List<AlertLogItem> {
        val items = mutableListOf<AlertLogItem>()
        val db = this.readableDatabase
        
        val cursor = db.query(
            TABLE_ALERT_LOG,
            null,
            "$ALERT_LOG_FEATURE_TYPE = ?",
            arrayOf(featureType.name),
            null,
            null,
            "$ALERT_LOG_CREATED_AT DESC"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = AlertLogItem(
                    time = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_TIME)),
                    type = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_TYPE)),
                    source = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_SOURCE)),
                    status = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_STATUS))
                )
                items.add(item)
            }
        }
        
        return items
    }
    
    /**
     * Get alert log items with pagination
     */
    fun getAlertLogItems(featureType: SelectFeatures, limit: Int, offset: Int): List<AlertLogItem> {
        val items = mutableListOf<AlertLogItem>()
        val db = this.readableDatabase
        
        val cursor = db.query(
            TABLE_ALERT_LOG,
            null,
            "$ALERT_LOG_FEATURE_TYPE = ?",
            arrayOf(featureType.name),
            null,
            null,
            "$ALERT_LOG_CREATED_AT DESC",
            "$limit OFFSET $offset"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = AlertLogItem(
                    time = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_TIME)),
                    type = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_TYPE)),
                    source = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_SOURCE)),
                    status = c.getString(c.getColumnIndexOrThrow(ALERT_LOG_STATUS))
                )
                items.add(item)
            }
        }
        
        return items
    }
    
    /**
     * Delete old alert log items (keep only recent N items)
     */
    fun cleanupOldAlertLogs(featureType: SelectFeatures, keepCount: Int = 1000): Int {
        val db = this.writableDatabase
        
        // Get IDs of items to keep
        val cursor = db.query(
            TABLE_ALERT_LOG,
            arrayOf(ALERT_LOG_ID),
            "$ALERT_LOG_FEATURE_TYPE = ?",
            arrayOf(featureType.name),
            null,
            null,
            "$ALERT_LOG_CREATED_AT DESC",
            keepCount.toString()
        )
        
        val idsToKeep = mutableListOf<String>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                idsToKeep.add(c.getString(0))
            }
        }
        
        return if (idsToKeep.isEmpty()) {
            0
        } else {
            val placeholders = idsToKeep.joinToString(",") { "?" }
            val selection = "$ALERT_LOG_FEATURE_TYPE = ? AND $ALERT_LOG_ID NOT IN ($placeholders)"
            val selectionArgs = arrayOf(featureType.name) + idsToKeep.toTypedArray()
            
            db.delete(TABLE_ALERT_LOG, selection, selectionArgs)
        }
    }
    
    /**
     * Get alert log count for a specific feature type
     */
    fun getAlertLogCount(featureType: SelectFeatures): Int {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_ALERT_LOG,
            arrayOf("COUNT(*)"),
            "$ALERT_LOG_FEATURE_TYPE = ?",
            arrayOf(featureType.name),
            null,
            null,
            null
        )
        
        return cursor?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    }

    // ==================== CONTENT LOG OPERATIONS ====================

    /**
     * Insert content log item
     */
    fun insertContentLogItem(item: ContentLogItem): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(CONTENT_LOG_TYPE, item.type)
            put(CONTENT_LOG_TIMESTAMP, item.timestamp)
            put(CONTENT_LOG_CONTENT, item.content)
            put(CONTENT_LOG_CREATED_AT, System.currentTimeMillis())
        }
        
        return try {
            val result = db.insert(TABLE_CONTENT_LOG, null, values)
            android.util.Log.d("DatabaseManager", "Content log item inserted: type=${item.type}, id=$result")
            result
        } catch (e: Exception) {
            android.util.Log.e("DatabaseManager", "Failed to insert content log item", e)
            -1
        }
    }

    /**
     * Get all content log items for a specific type
     */
    fun getContentLogItems(type: String): List<ContentLogItem> {
        val items = mutableListOf<ContentLogItem>()
        val db = this.readableDatabase
        
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            null,
            "$CONTENT_LOG_TYPE = ?",
            arrayOf(type),
            null,
            null,
            "$CONTENT_LOG_TIMESTAMP DESC"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = ContentLogItem(
                    id = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_ID)),
                    type = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_TYPE)),
                    timestamp = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_TIMESTAMP)),
                    content = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_CONTENT))
                )
                items.add(item)
            }
        }
        
        return items
    }

    /**
     * Get all content log items (all types)
     */
    fun getAllContentLogItems(): List<ContentLogItem> {
        val items = mutableListOf<ContentLogItem>()
        val db = this.readableDatabase
        
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            null,
            null,
            null,
            null,
            null,
            "$CONTENT_LOG_TIMESTAMP DESC"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = ContentLogItem(
                    id = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_ID)),
                    type = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_TYPE)),
                    timestamp = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_TIMESTAMP)),
                    content = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_CONTENT))
                )
                items.add(item)
            }
        }
        
        return items
    }

    /**
     * Get content log items with pagination
     */
    fun getContentLogItems(type: String, limit: Int, offset: Int): List<ContentLogItem> {
        val items = mutableListOf<ContentLogItem>()
        val db = this.readableDatabase
        
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            null,
            "$CONTENT_LOG_TYPE = ?",
            arrayOf(type),
            null,
            null,
            "$CONTENT_LOG_TIMESTAMP DESC",
            "$limit OFFSET $offset"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = ContentLogItem(
                    id = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_ID)),
                    type = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_TYPE)),
                    timestamp = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_TIMESTAMP)),
                    content = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_CONTENT))
                )
                items.add(item)
            }
        }
        
        return items
    }

    /**
     * Get content log count for a specific type
     */
    fun getContentLogCount(type: String): Int {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            arrayOf("COUNT(*)"),
            "$CONTENT_LOG_TYPE = ?",
            arrayOf(type),
            null,
            null,
            null
        )
        
        return cursor?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    }

    /**
     * Get total content log count (all types)
     */
    fun getTotalContentLogCount(): Int {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            arrayOf("COUNT(*)"),
            null,
            null,
            null,
            null,
            null
        )
        
        return cursor?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    }

    /**
     * Delete content log item by ID
     */
    fun deleteContentLogItem(id: Long): Int {
        val db = this.writableDatabase
        return db.delete(
            TABLE_CONTENT_LOG,
            "$CONTENT_LOG_ID = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Delete all content log items for a specific type
     */
    fun deleteContentLogItemsByType(type: String): Int {
        val db = this.writableDatabase
        return db.delete(
            TABLE_CONTENT_LOG,
            "$CONTENT_LOG_TYPE = ?",
            arrayOf(type)
        )
    }

    /**
     * Delete old content log items (keep only recent N items per type)
     */
    fun cleanupOldContentLogs(type: String, keepCount: Int = 1000): Int {
        val db = this.writableDatabase
        
        // Get IDs of items to keep
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            arrayOf(CONTENT_LOG_ID),
            "$CONTENT_LOG_TYPE = ?",
            arrayOf(type),
            null,
            null,
            "$CONTENT_LOG_TIMESTAMP DESC",
            keepCount.toString()
        )
        
        val idsToKeep = mutableListOf<String>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                idsToKeep.add(c.getString(0))
            }
        }
        
        return if (idsToKeep.isEmpty()) {
            0
        } else {
            val placeholders = idsToKeep.joinToString(",") { "?" }
            val selection = "$CONTENT_LOG_TYPE = ? AND $CONTENT_LOG_ID NOT IN ($placeholders)"
            val selectionArgs = arrayOf(type) + idsToKeep.toTypedArray()
            
            db.delete(TABLE_CONTENT_LOG, selection, selectionArgs)
        }
    }

    /**
     * Search content log items by content text
     */
    fun searchContentLogItems(query: String, type: String? = null): List<ContentLogItem> {
        val items = mutableListOf<ContentLogItem>()
        val db = this.readableDatabase
        
        val selection = if (type != null) {
            "$CONTENT_LOG_CONTENT LIKE ? AND $CONTENT_LOG_TYPE = ?"
        } else {
            "$CONTENT_LOG_CONTENT LIKE ?"
        }
        
        val selectionArgs = if (type != null) {
            arrayOf("%$query%", type)
        } else {
            arrayOf("%$query%")
        }
        
        val cursor = db.query(
            TABLE_CONTENT_LOG,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$CONTENT_LOG_TIMESTAMP DESC"
        )
        
        cursor?.use { c ->
            while (c.moveToNext()) {
                val item = ContentLogItem(
                    id = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_ID)),
                    type = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_TYPE)),
                    timestamp = c.getLong(c.getColumnIndexOrThrow(CONTENT_LOG_TIMESTAMP)),
                    content = c.getString(c.getColumnIndexOrThrow(CONTENT_LOG_CONTENT))
                )
                items.add(item)
            }
        }
        
        return items
    }

    /**
     * Clear all data (useful for testing or reset)
     */
    fun clearAllData() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_ALLOWLIST")
        db.execSQL("DELETE FROM $TABLE_ALERT_LOG")
        db.execSQL("DELETE FROM $TABLE_CONTENT_LOG")
    }
    
    /**
     * Get database statistics
     */
    fun getDatabaseStats(): DatabaseStats {
        val db = this.readableDatabase
        
        val allowlistCounts = SelectFeatures.values().associateWith { feature ->
            val cursor = db.query(
                TABLE_ALLOWLIST,
                arrayOf("COUNT(*)"),
                "$ALLOWLIST_FEATURE_TYPE = ?",
                arrayOf(feature.name),
                null, null, null
            )
            cursor?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
        }
        
        val alertLogCounts = SelectFeatures.values().associateWith { feature ->
            val cursor = db.query(
                TABLE_ALERT_LOG,
                arrayOf("COUNT(*)"),
                "$ALERT_LOG_FEATURE_TYPE = ?",
                arrayOf(feature.name),
                null, null, null
            )
            cursor?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
        }
        
        // Get content log counts by type (using distinct types from the table)
        val contentLogTypes = mutableSetOf<String>()
        val typesCursor = db.query(
            TABLE_CONTENT_LOG,
            arrayOf("DISTINCT $CONTENT_LOG_TYPE"),
            null, null, null, null, null
        )
        typesCursor?.use { c ->
            while (c.moveToNext()) {
                contentLogTypes.add(c.getString(0))
            }
        }
        
        val contentLogCounts = contentLogTypes.associateWith { type ->
            val cursor = db.query(
                TABLE_CONTENT_LOG,
                arrayOf("COUNT(*)"),
                "$CONTENT_LOG_TYPE = ?",
                arrayOf(type),
                null, null, null
            )
            cursor?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
        }
        
        return DatabaseStats(allowlistCounts, alertLogCounts, contentLogCounts)
    }
}

/**
 * Database statistics data class
 */
data class DatabaseStats(
    val allowlistCounts: Map<SelectFeatures, Int>,
    val alertLogCounts: Map<SelectFeatures, Int>,
    val contentLogCounts: Map<String, Int>
) {
    val totalAllowlistItems: Int get() = allowlistCounts.values.sum()
    val totalAlertLogItems: Int get() = alertLogCounts.values.sum()
    val totalContentLogItems: Int get() = contentLogCounts.values.sum()
}
