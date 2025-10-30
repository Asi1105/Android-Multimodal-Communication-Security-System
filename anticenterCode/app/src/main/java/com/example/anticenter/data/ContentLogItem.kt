package com.example.anticenter.data

/**
 * Content log item data class for storing content data from PhishingDataHub
 * with type aligned to log table types
 */
data class ContentLogItem(
    val id: Long = 0, // Auto-generated ID
    val type: String, // Type aligned with log table (e.g., "Zoom", "Email", "PhoneCall")
    val timestamp: Long, // Unix timestamp in milliseconds
    val content: String // The actual content data
) {
    /**
     * Constructor with formatted time string
     */
    constructor(
        type: String,
        timeString: String,
        content: String
    ) : this(
        id = 0,
        type = type,
        timestamp = System.currentTimeMillis(),
        content = content
    )
    
    /**
     * Get formatted time string from timestamp
     */
    fun getFormattedTime(): String {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return formatter.format(java.util.Date(timestamp))
    }
}