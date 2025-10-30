package com.example.anticenter.services

import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.PhishingData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PhishingData 转换工具类
 * 从 CoreProtectionService 提取的纯函数逻辑,便于测试
 */
object PhishingDataConverter {
    
    /**
     * 将 PhishingData 转换为 AlertLogItem
     */
    fun convertToAlertItem(phishingData: PhishingData): AlertLogItem? {
        return try {
            val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            when (phishingData.dataType.lowercase()) {
                "email" -> convertEmailData(phishingData, currentTime)
                "zoom" -> convertZoomData(phishingData, currentTime)
                "phonecall" -> convertPhoneCallData(phishingData, currentTime)
                else -> {
                    android.util.Log.w("PhishingDataConverter", "Unknown data type: ${phishingData.dataType}")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhishingDataConverter", "Error converting PhishingData", e)
            null
        }
    }
    
    private fun convertEmailData(data: PhishingData, currentTime: String): AlertLogItem {
        val llmDecision = data.metadata["llmDecision"]
        val sender = data.metadata["sender"] ?: "Unknown Sender"
        val subject = data.metadata["subject"] ?: "No Subject"
        
        return AlertLogItem(
            time = currentTime,
            type = if (llmDecision == "phishing") "Phishing Email" else "Suspicious Email",
            source = "$sender (Subject: $subject)",
            status = "Detected"
        )
    }
    
    private fun convertZoomData(data: PhishingData, currentTime: String): AlertLogItem {
        val mediaType = data.metadata["mediaType"] ?: ""
        val fileName = data.metadata["fileName"] ?: "Unknown File"
        
        val attackCategory = when (mediaType.uppercase()) {
            "VIDEO" -> "Deepfake Video"
            "AUDIO" -> "Deepfake Audio"
            "AUDIO_PHISHING" -> "Voice Phishing"
            "IMAGE" -> "Deepfake Image"
            else -> "Unknown Threat"
        }
        
        return AlertLogItem(
            time = currentTime,
            type = "Suspicious Meeting",
            source = "$attackCategory ($fileName)",
            status = "Detected"
        )
    }
    
    private fun convertPhoneCallData(data: PhishingData, currentTime: String): AlertLogItem {
        val source = data.metadata["source"] ?: "Unknown Number"
        return AlertLogItem(
            time = currentTime,
            type = "Suspicious Call",
            source = source,
            status = "Detected"
        )
    }
    
    /**
     * 将数据类型映射到对应的功能模块
     */
    fun mapDataTypeToFeature(dataType: String): SelectFeatures {
        return when (dataType.lowercase()) {
            "email" -> SelectFeatures.emailProtection
            "zoom" -> SelectFeatures.meetingProtection
            "phonecall" -> SelectFeatures.callProtection
            else -> SelectFeatures.callProtection // Default fallback
        }
    }
}
