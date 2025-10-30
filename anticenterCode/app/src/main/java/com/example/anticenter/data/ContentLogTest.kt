package com.example.anticenter.data

import android.content.Context
import com.example.anticenter.database.AntiCenterRepository
import kotlinx.coroutines.runBlocking

/**
 * Test utility to verify the new content log functionality
 * This can be used in unit tests or for manual testing
 */
class ContentLogTest {
    
    companion object {
        /**
         * Run a comprehensive test of the content log functionality
         * Call this method from your MainActivity or test class
         */
        fun runContentLogTest(context: Context) {
            val repository = AntiCenterRepository.getInstance(context)
            
            runBlocking {
                println("=== Content Log Test Started ===")
                
                // Test 1: Add content log items
                println("\n1. Adding content log items...")
                val testItems = listOf(
                    ContentLogItem(
                        type = "Email",
                        timestamp = System.currentTimeMillis(),
                        content = "Suspicious email: Your account will be closed unless you verify..."
                    ),
                    ContentLogItem(
                        type = "Zoom", 
                        timestamp = System.currentTimeMillis() - 1000000,
                        content = "Zoom meeting: Suspicious host requesting bank details"
                    ),
                    ContentLogItem(
                        type = "PhoneCall",
                        timestamp = System.currentTimeMillis() - 2000000,
                        content = "Phone call transcript: Caller claiming to be from IRS requesting SSN"
                    )
                )
                
                testItems.forEach { item ->
                    val result = repository.addContentLogItem(item)
                    if (result.isSuccess) {
                        println("✓ Added ${item.type} content with ID: ${result.getOrNull()}")
                    } else {
                        println("✗ Failed to add ${item.type} content: ${result.exceptionOrNull()?.message}")
                    }
                }
                
                // Test 2: Retrieve content by type
                println("\n2. Retrieving content by type...")
                val emailItems = repository.getContentLogItems("Email")
                println("✓ Found ${emailItems.size} Email items")
                
                val zoomItems = repository.getContentLogItems("Zoom")
                println("✓ Found ${zoomItems.size} Zoom items")
                
                val phoneItems = repository.getContentLogItems("PhoneCall")
                println("✓ Found ${phoneItems.size} PhoneCall items")
                
                // Test 3: Retrieve all content
                println("\n3. Retrieving all content...")
                val allItems = repository.getAllContentLogItems()
                println("✓ Found ${allItems.size} total content items")
                
                // Test 4: Search content
                println("\n4. Searching content...")
                val searchResults = repository.searchContentLogItems("suspicious")
                println("✓ Found ${searchResults.size} items containing 'suspicious'")
                
                // Test 5: Get counts
                println("\n5. Getting content counts...")
                val emailCount = repository.getContentLogCount("Email")
                val totalCount = repository.getTotalContentLogCount()
                println("✓ Email count: $emailCount, Total count: $totalCount")
                
                // Test 6: Get database stats
                println("\n6. Getting database statistics...")
                val stats = repository.getDatabaseStats()
                println("✓ Total content log items: ${stats.totalContentLogItems}")
                stats.contentLogCounts.forEach { (type, count) ->
                    println("  - $type: $count items")
                }
                
                // Test 7: PhishingDataHub integration
                println("\n7. Testing PhishingDataHub integration...")
                val emailPhishingData = PhishingData(
                    dataType = "Email",
                    content = "Test phishing email content from DataHub",
                    metadata = mapOf(
                        "subject" to "Urgent: Account Verification Required",
                        "sender" to "fake@scammer.com"
                    )
                )
                PhishingDataHub.addData(emailPhishingData)
                println("✓ Added email data via PhishingDataHub")
                
                val zoomPhishingData = PhishingData(
                    dataType = "Zoom",
                    content = "Test zoom meeting content from DataHub",
                    metadata = mapOf(
                        "meetingId" to "123-456-789",
                        "host" to "suspicious@host.com"
                    )
                )
                PhishingDataHub.addData(zoomPhishingData)
                println("✓ Added zoom data via PhishingDataHub")
                
                // Wait a moment for data to be processed
                kotlinx.coroutines.delay(1000)
                
                val newTotalCount = repository.getTotalContentLogCount()
                println("✓ New total count after PhishingDataHub: $newTotalCount")
                
                println("\n=== Content Log Test Completed Successfully ===")
            }
        }
        
        /**
         * Clean up test data
         */
        fun cleanupTestData(context: Context) {
            val repository = AntiCenterRepository.getInstance(context)
            
            runBlocking {
                println("=== Cleaning up test data ===")
                
                // Delete test content logs
                val types = listOf("Email", "Zoom", "PhoneCall")
                types.forEach { type ->
                    val result = repository.deleteContentLogItemsByType(type)
                    if (result.isSuccess) {
                        println("✓ Cleaned up $type content logs")
                    } else {
                        println("✗ Failed to cleanup $type content logs: ${result.exceptionOrNull()?.message}")
                    }
                }
                
                println("=== Cleanup completed ===")
            }
        }
    }
}