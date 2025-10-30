package com.example.anticenter.utils

import android.content.Context
import com.example.anticenter.data.ContentLogItem
import com.example.anticenter.database.AntiCenterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 测试数据生成器，用于生成示例content log数据来测试CSV导出功能
 */
class TestDataGenerator {
    
    companion object {
        /**
         * 生成测试数据并保存到数据库
         */
        fun generateTestData(context: Context) {
            val repository = AntiCenterRepository.getInstance(context)
            val scope = CoroutineScope(Dispatchers.IO)
            
            scope.launch {
                // 生成邮件保护测试数据
                val emailData = listOf(
                    ContentLogItem(
                        type = "Email",
                        timestamp = System.currentTimeMillis() - 86400000, // 1天前
                        content = "Subject: Urgent Account Verification\nFrom: security@fake-bank.com\nBody: Your account has been suspended. Click here to verify: http://fake-bank.scam.com"
                    ),
                    ContentLogItem(
                        type = "Email", 
                        timestamp = System.currentTimeMillis() - 43200000, // 12小时前
                        content = "Subject: Prize Winner!\nFrom: lottery@scammer.net\nBody: Congratulations! You've won $1,000,000. Send us your bank details to claim your prize."
                    ),
                    ContentLogItem(
                        type = "Email",
                        timestamp = System.currentTimeMillis() - 3600000, // 1小时前
                        content = "Subject: IRS Tax Refund\nFrom: irs@fake-gov.com\nBody: You are eligible for a tax refund of $5,000. Please provide your SSN and bank information."
                    )
                )
                
                // 生成会议保护测试数据
                val zoomData = listOf(
                    ContentLogItem(
                        type = "Zoom",
                        timestamp = System.currentTimeMillis() - 172800000, // 2天前
                        content = "Meeting ID: 123-456-789\nHost: suspicious-host@scammer.com\nTopic: Important Bank Account Information\nJoin URL: https://fake-zoom.scam.com/j/123456789"
                    ),
                    ContentLogItem(
                        type = "Zoom",
                        timestamp = System.currentTimeMillis() - 7200000, // 2小时前
                        content = "Meeting ID: 987-654-321\nHost: fake-support@company.scam\nTopic: Urgent Security Update Required\nJoin URL: https://malicious-meeting.com/join/987654321"
                    )
                )
                
                // 生成电话保护测试数据
                val phoneData = listOf(
                    ContentLogItem(
                        type = "PhoneCall",
                        timestamp = System.currentTimeMillis() - 259200000, // 3天前
                        content = "Caller: +1-555-SCAM-123\nDuration: 5:30\nTranscript: Hello, this is from your bank. We need to verify your credit card information immediately..."
                    ),
                    ContentLogItem(
                        type = "PhoneCall",
                        timestamp = System.currentTimeMillis() - 10800000, // 3小时前
                        content = "Caller: +1-800-FAKE-IRS\nDuration: 8:45\nTranscript: This is the IRS. You owe $10,000 in back taxes. Pay immediately or face arrest..."
                    ),
                    ContentLogItem(
                        type = "PhoneCall",
                        timestamp = System.currentTimeMillis() - 1800000, // 30分钟前
                        content = "Caller: +1-555-TECH-999\nDuration: 12:15\nTranscript: Your computer has been infected with a virus. We need remote access to fix it..."
                    )
                )
                
                // 生成URL保护测试数据
                val urlData = listOf(
                    ContentLogItem(
                        type = "URL",
                        timestamp = System.currentTimeMillis() - 345600000, // 4天前
                        content = "URL: https://fake-amazon.scam.com/login\nReferrer: phishing-email\nRisk: High - Fake shopping site impersonating Amazon"
                    ),
                    ContentLogItem(
                        type = "URL",
                        timestamp = System.currentTimeMillis() - 14400000, // 4小时前
                        content = "URL: https://malware-download.net/virus.exe\nReferrer: suspicious-ad\nRisk: Critical - Malware download attempt"
                    )
                )
                
                // 保存所有测试数据
                val allTestData = emailData + zoomData + phoneData + urlData
                
                allTestData.forEach { item ->
                    try {
                        val result = repository.addContentLogItem(item)
                        if (result.isSuccess) {
                            android.util.Log.d("TestDataGenerator", "Added test ${item.type} data with ID: ${result.getOrNull()}")
                        } else {
                            android.util.Log.w("TestDataGenerator", "Failed to add test ${item.type} data: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TestDataGenerator", "Error adding test data", e)
                    }
                }
                
                android.util.Log.i("TestDataGenerator", "Test data generation completed. Added ${allTestData.size} items.")
            }
        }
        
        /**
         * 清理测试数据
         */
        fun clearTestData(context: Context) {
            val repository = AntiCenterRepository.getInstance(context)
            val scope = CoroutineScope(Dispatchers.IO)
            
            scope.launch {
                try {
                    val types = listOf("Email", "Zoom", "PhoneCall", "URL") 
                    types.forEach { type ->
                        val result = repository.deleteContentLogItemsByType(type)
                        if (result.isSuccess) {
                            android.util.Log.d("TestDataGenerator", "Cleared test $type data")
                        }
                    }
                    android.util.Log.i("TestDataGenerator", "Test data cleanup completed")
                } catch (e: Exception) {
                    android.util.Log.e("TestDataGenerator", "Error clearing test data", e)
                }
            }
        }
        
        /**
         * 获取测试数据统计
         */
        fun getTestDataStats(context: Context, onResult: (Map<String, Int>) -> Unit) {
            val repository = AntiCenterRepository.getInstance(context)
            val scope = CoroutineScope(Dispatchers.IO)
            
            scope.launch {
                try {
                    val stats = mutableMapOf<String, Int>()
                    val types = listOf("Email", "Zoom", "PhoneCall", "URL")
                    
                    types.forEach { type ->
                        val count = repository.getContentLogCount(type)
                        stats[type] = count
                    }
                    
                    onResult(stats)
                } catch (e: Exception) {
                    android.util.Log.e("TestDataGenerator", "Error getting test data stats", e)
                    onResult(emptyMap())
                }
            }
        }
    }
}