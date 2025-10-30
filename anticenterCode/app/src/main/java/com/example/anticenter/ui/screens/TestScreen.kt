package com.example.anticenter.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.anticenter.R
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.AlertLogViewModel
import com.example.anticenter.services.OverlayBannerService
import com.example.anticenter.services.CoreProtectionService
import com.example.anticenter.data.PhishingDataHub
import com.example.anticenter.data.PhishingData
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val alertLogViewModel: AlertLogViewModel = viewModel()
    val coroutineScope = rememberCoroutineScope()
    
    // State management
    var operationStatus by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    
    fun startOverlay(message: String, delayMs: Long) {
        val intent = Intent(context, OverlayBannerService::class.java).apply {
            action = OverlayBannerService.ACTION_SHOW
            putExtra(OverlayBannerService.EXTRA_MESSAGE, message)
            putExtra(OverlayBannerService.EXTRA_DELAY_MS, delayMs)
        }
        context.startServiceCompat(intent)
    }
    fun hideOverlay() {
        val intent = Intent(context, OverlayBannerService::class.java).apply {
            action = OverlayBannerService.ACTION_HIDE
        }
        context.startServiceCompat(intent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AntiCenter Test Playground", style = MaterialTheme.typography.headlineSmall)
        Text("Test overlay, database operations and notification features")
        
        // Display operation status
        if (operationStatus.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (operationStatus.contains("Error")) 
                        MaterialTheme.colorScheme.errorContainer 
                    else MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = operationStatus,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Database test section
        DatabaseTestSection(
            alertLogViewModel = alertLogViewModel,
            onStatusUpdate = { status -> operationStatus = status }
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        // PhishingDataHub test section
        PhishingDataHubTestSection(
            coroutineScope = coroutineScope,
            onStatusUpdate = { status -> operationStatus = status }
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        // Original Overlay test section
        Text("Overlay Test Section", style = MaterialTheme.typography.headlineSmall)
        Text("Trigger cross-app overlay with different messages and delays.")

        // Show immediately - short message
        Button(onClick = {
            startOverlay(
                message = "Suspicious website detected!",
                delayMs = 0L
            )
        }) { Text("Show Immediately · Short Message") }

        // Show after 3s - standard message
        Button(onClick = {
            startOverlay(
                message = "Suspicious website detected! Please stop immediately.",
                delayMs = 3_000L
            )
        }) { Text("Show After 3s · Standard Message") }

        // Show after 10s - cross-app test (convenient to switch to other apps)
        Button(onClick = {
            startOverlay(
                message = "Suspicious activity detected across apps. This banner should appear even when you switch apps.",
                delayMs = 10_000L
            )
        }) { Text("Show After 10s · Cross-App Test") }

        // Long message - truncation and expand test
        Button(onClick = {
            startOverlay(
                message = "This is a very long warning message to verify truncation and expandable behavior. Please review the details below carefully to ensure that the user experience matches the design specification across various device sizes and orientations.",
                delayMs = 0L
            )
        }) { Text("Show Immediately · Long Message (Expand Test)") }

        // Chinese message - fraud transfer warning
        Button(onClick = {
            startOverlay(
                message = "Suspicious transfer risk detected! This operation may involve fraud, please stop immediately and verify recipient information.",
                delayMs = 0L
            )
        }) { Text("Show Immediately · Chinese Message") }

        // Very long URL/numbers - expand alignment
        Button(onClick = {
            startOverlay(
                message = "Review: https://example.com/really/long/path/that/keeps/going/and/going/1234567890ABCDEFGHijklmnOPQRSTuvwxYZ",
                delayMs = 0L
            )
        }) { Text("Show Immediately · Very Long URL/Numbers") }

        // Continuous scheduling test: first 5s, then 1s (latter should override former)
        Button(onClick = {
            startOverlay(
                message = "This should be overridden by the next schedule (5s)",
                delayMs = 5_000L
            )
            startOverlay(
                message = "Latest schedule wins (1s)",
                delayMs = 1_000L
            )
        }) { Text("Continuous Schedule Test · Latter Overrides Former") }

        // Hide Overlay
        Button(onClick = { hideOverlay() }) { Text("Hide Overlay") }

        // Send queue test event (deliver to CoreProtectionService queue)
        Button(onClick = {
            val now = System.currentTimeMillis()
            val intent = Intent(context, CoreProtectionService::class.java).apply {
                action = CoreProtectionService.ACTION_NOTIFICATION_TEXT
                putExtra(CoreProtectionService.EXTRA_SRC_PACKAGE, context.packageName)
                putExtra(CoreProtectionService.EXTRA_NOTIFICATION_ID, (now % Int.MAX_VALUE).toInt())
                putExtra(CoreProtectionService.EXTRA_POST_TIME, now)
                putExtra(CoreProtectionService.EXTRA_CHANNEL_ID, "test")
                putExtra(CoreProtectionService.EXTRA_CATEGORY, "test")
                putExtra(CoreProtectionService.EXTRA_IS_ONGOING, false)
                putExtra(CoreProtectionService.EXTRA_TITLE, "Test from TestScreen")
                putExtra(CoreProtectionService.EXTRA_PAYLOAD_TEXT, "This is a test event from TestScreen to CoreProtectionService queue.")
            }
            context.startServiceCompat(intent)
        }) { Text("Send Queue Test Event") }

        // Send system notification - test listener and processor pipeline
        Button(onClick = { context.postTestNotification() }) { Text("Send System Notification · Test Listener Pipeline") }
    }
}

@Composable
fun PhishingDataHubTestSection(
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onStatusUpdate: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "PhishingDataHub Test Section", 
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Test data flow through PhishingDataHub → CoreProtectionService → Database",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 为每个功能添加通过 DataHub 测试的按钮
        Text("Send Test Data via PhishingDataHub:", style = MaterialTheme.typography.titleMedium)
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Phishing email test
            Button(
                onClick = {
                    coroutineScope.launch {
                        sendTestPhishingEmail(onStatusUpdate)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Phishing Email", style = MaterialTheme.typography.bodySmall)
            }
            
            // Suspicious meeting test
            Button(
                onClick = {
                    coroutineScope.launch {
                        sendTestSuspiciousMeeting(onStatusUpdate)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Suspicious Meeting", style = MaterialTheme.typography.bodySmall)
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Suspicious call test
            Button(
                onClick = {
                    coroutineScope.launch {
                        sendTestSuspiciousCall(onStatusUpdate)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Suspicious Call", style = MaterialTheme.typography.bodySmall)
            }
            
            // Batch test
            Button(
                onClick = {
                    coroutineScope.launch {
                        sendBatchTestData(onStatusUpdate)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Batch Test", style = MaterialTheme.typography.bodySmall)
            }
        }
        
        // Explanation text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = "💡 These buttons send PhishingData to PhishingDataHub, which triggers CoreProtectionService to process and save to database automatically.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DatabaseTestSection(
    alertLogViewModel: AlertLogViewModel,
    onStatusUpdate: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Database Test Section", 
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Add sample alert logs for different protection features",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Add sample data buttons for each feature
        Text("Add Sample Alert Logs:", style = MaterialTheme.typography.titleMedium)
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Call protection
            OutlinedButton(
                onClick = {
                    addSampleCallLogs(alertLogViewModel, onStatusUpdate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Call", style = MaterialTheme.typography.bodySmall)
            }
            
            // Email protection
            OutlinedButton(
                onClick = {
                    addSampleEmailLogs(alertLogViewModel, onStatusUpdate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Email", style = MaterialTheme.typography.bodySmall)
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // URL protection
            OutlinedButton(
                onClick = {
                    addSampleUrlLogs(alertLogViewModel, onStatusUpdate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("URL", style = MaterialTheme.typography.bodySmall)
            }
            
            // Meeting protection
            OutlinedButton(
                onClick = {
                    addSampleMeetingLogs(alertLogViewModel, onStatusUpdate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Meeting", style = MaterialTheme.typography.bodySmall)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Batch operation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Add sample data for all types
            Button(
                onClick = {
                    addAllSampleLogs(alertLogViewModel, onStatusUpdate)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add All Sample Data")
            }
            
            // Clear database
            Button(
                onClick = {
                    clearAllData(alertLogViewModel, onStatusUpdate)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear All Data", color = Color.White)
            }
        }
    }
}

private fun Context.startServiceCompat(intent: Intent) {
    // Keep it as a regular short-lived service; we don't promote to foreground here.
    // This call is made while app is in foreground (TestScreen), so it's permitted.
    try { startService(intent) } catch (_: Exception) {}
}

private fun Context.postTestNotification() {
    val channelId = "test_listener_channel"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "AntiCenter Test Listener",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Channel for testing notification listener" }
            )
        }
    }

    // Android 13+ requires POST_NOTIFICATIONS runtime permission
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(this, "Missing notification permission, cannot send system notification", Toast.LENGTH_SHORT).show()
            return
        }
    }

    val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    val notification = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("AntiCenter Test Notification")
        .setContentText("Posted at ${System.currentTimeMillis()}")
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                "This is a system notification posted by TestScreen to exercise NotificationListenerService and CoreProtectionService pipeline."
            )
        )
        .setAutoCancel(true)
        .build()

    try {
        NotificationManagerCompat.from(this).notify(id, notification)
    } catch (e: SecurityException) {
        Toast.makeText(this, "Failed to send notification (insufficient permissions)", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) { }
}

// PhishingDataHub test functions
private suspend fun sendTestPhishingEmail(onStatusUpdate: (String) -> Unit) {
    try {
        onStatusUpdate("Sending test phishing email to PhishingDataHub...")
        
        val testEmailData = PhishingData(
            dataType = "Email",
            content = "URGENT: Your account will be suspended! Click here to verify immediately: https://fake-bank-security.com/verify?token=abc123",
            metadata = mapOf(
                "subject" to "URGENT: Account Verification Required",
                "sender" to "security@fake-bank.com",
                "snippet" to "Your account shows suspicious activity...",
                "dateReceived" to generateCurrentTime(),
                "messageId" to "test_phishing_${System.currentTimeMillis()}",
                "threadId" to "thread_123",
                "timestamp" to System.currentTimeMillis().toString(),
                "llmDecision" to "phishing",
                "llmExplanation" to "This email contains urgent language, suspicious links, and impersonates a financial institution to steal credentials."
            )
        )
        
        PhishingDataHub.addData(testEmailData)
        onStatusUpdate("✅ Test phishing email sent to PhishingDataHub successfully!")
        
    } catch (e: Exception) {
        onStatusUpdate("❌ Error sending test phishing email: ${e.message}")
    }
}

private suspend fun sendTestSuspiciousMeeting(onStatusUpdate: (String) -> Unit) {
    try {
        onStatusUpdate("Sending test suspicious meeting to PhishingDataHub...")
        
        val testMeetingData = PhishingData(
            dataType = "Zoom",
            content = "Emergency CEO meeting - Urgent financial discussion. Join immediately or face consequences.",
            metadata = mapOf(
                "source" to "zoom://meeting/fake-ceo-emergency-456",
                "meetingId" to "456-789-012",
                "host" to "fake.ceo@company-imposter.com",
                "title" to "URGENT: CEO Emergency Meeting",
                "participants" to "3",
                "timestamp" to System.currentTimeMillis().toString(),
                "riskLevel" to "high",
                "detectionReason" to "Impersonation attempt and urgency tactics detected"
            )
        )
        
        PhishingDataHub.addData(testMeetingData)
        onStatusUpdate("✅ Test suspicious meeting sent to PhishingDataHub successfully!")
        
    } catch (e: Exception) {
        onStatusUpdate("❌ Error sending test suspicious meeting: ${e.message}")
    }
}

private suspend fun sendTestSuspiciousCall(onStatusUpdate: (String) -> Unit) {
    try {
        onStatusUpdate("Sending test suspicious call to PhishingDataHub...")
        
        val testCallData = PhishingData(
            dataType = "PhoneCall",
            content = "This is Microsoft technical support. Your computer has been compromised and we need remote access to fix it immediately.",
            metadata = mapOf(
                "source" to "+1-555-SCAM-123",
                "duration" to "0", // Incoming call, not answered yet
                "callType" to "incoming",
                "timestamp" to System.currentTimeMillis().toString(),
                "riskLevel" to "high",
                "detectionReason" to "Known scam pattern: fake technical support with urgency tactics"
            )
        )
        
        PhishingDataHub.addData(testCallData)
        onStatusUpdate("✅ Test suspicious call sent to PhishingDataHub successfully!")
        
    } catch (e: Exception) {
        onStatusUpdate("❌ Error sending test suspicious call: ${e.message}")
    }
}

private suspend fun sendBatchTestData(onStatusUpdate: (String) -> Unit) {
    try {
        onStatusUpdate("Sending batch test data to PhishingDataHub...")
        
        // Send multiple types of test data
        val timestamp = System.currentTimeMillis()
        
        // 1. Phishing email
        val phishingEmail = PhishingData(
            dataType = "Email",
            content = "Congratulations! You've won $10,000! Click to claim your prize now!",
            metadata = mapOf(
                "subject" to "🎉 YOU WON $10,000!!! CLAIM NOW!!!",
                "sender" to "winner@fake-lottery.scam",
                "llmDecision" to "phishing",
                "llmExplanation" to "Classic lottery scam with excessive urgency and fake prize claims",
                "timestamp" to timestamp.toString()
            )
        )
        
        // 2. Spam email (safe)
        val spamEmail = PhishingData(
            dataType = "Email",
            content = "Discount sale on electronics - up to 50% off this weekend only!",
            metadata = mapOf(
                "subject" to "Weekend Electronics Sale - 50% Off",
                "sender" to "sales@electronics-store.com",
                "llmDecision" to "safe",
                "llmExplanation" to "Promotional email from legitimate retailer",
                "timestamp" to (timestamp + 1000).toString()
            )
        )
        
        // 3. Suspicious meeting
        val suspiciousMeeting = PhishingData(
            dataType = "Zoom",
            content = "Fake job interview - Please share your SSN and bank details for background check",
            metadata = mapOf(
                "source" to "zoom://meeting/fake-interview-789",
                "title" to "Job Interview - Senior Developer Position",
                "host" to "hr@fake-company.scam",
                "riskLevel" to "critical",
                "timestamp" to (timestamp + 2000).toString()
            )
        )
        
        // Send data
        PhishingDataHub.addData(phishingEmail)
        PhishingDataHub.addData(spamEmail)
        PhishingDataHub.addData(suspiciousMeeting)
        
        onStatusUpdate("✅ Batch test data sent successfully! (3 items: 2 emails + 1 meeting)")
        
    } catch (e: Exception) {
        onStatusUpdate("❌ Error sending batch test data: ${e.message}")
    }
}

// Mock data generation functions
private fun generateCurrentTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date())
}

private fun addSampleCallLogs(alertLogViewModel: AlertLogViewModel, onStatusUpdate: (String) -> Unit) {
    val sampleLogs = listOf(
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Suspicious Call",
            source = "+1-555-SCAM1",
            status = "Blocked"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Spam Call",
            source = "+86-138-****-0000",
            status = "Warning"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Robocall",
            source = "+1-800-ROBOT",
            status = "Blocked"
        )
    )
    
    sampleLogs.forEach { log ->
        alertLogViewModel.addAlertLog(log, SelectFeatures.callProtection)
    }
    onStatusUpdate("Added ${sampleLogs.size} call protection logs")
}

private fun addSampleEmailLogs(alertLogViewModel: AlertLogViewModel, onStatusUpdate: (String) -> Unit) {
    val sampleLogs = listOf(
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Phishing Email",
            source = "fake-bank@scammer.com",
            status = "Blocked"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Spam Email",
            source = "noreply@suspicious-offer.net",
            status = "Warning"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Malware Email",
            source = "virus@malware-site.org",
            status = "Blocked"
        )
    )
    
    sampleLogs.forEach { log ->
        alertLogViewModel.addAlertLog(log, SelectFeatures.emailProtection)
    }
    onStatusUpdate("Added ${sampleLogs.size} email protection logs")
}

private fun addSampleUrlLogs(alertLogViewModel: AlertLogViewModel, onStatusUpdate: (String) -> Unit) {
    val sampleLogs = listOf(
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Malicious URL",
            source = "https://fake-banking-site.com",
            status = "Blocked"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Phishing URL",
            source = "https://secure-login-fake.net",
            status = "Warning"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Suspicious Download",
            source = "https://virus-download.co",
            status = "Blocked"
        )
    )
    
    sampleLogs.forEach { log ->
        alertLogViewModel.addAlertLog(log, SelectFeatures.urlProtection)
    }
    onStatusUpdate("Added ${sampleLogs.size} URL protection logs")
}

private fun addSampleMeetingLogs(alertLogViewModel: AlertLogViewModel, onStatusUpdate: (String) -> Unit) {
    val sampleLogs = listOf(
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Suspicious Meeting",
            source = "zoom://meeting/fake-urgent-123",
            status = "Warning"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Phishing Meeting",
            source = "teams://fake-ceo-meeting/456",
            status = "Blocked"
        ),
        AlertLogItem(
            time = generateCurrentTime(),
            type = "Fake Interview",
            source = "meet.google.com/suspicious-job",
            status = "Blocked"
        )
    )
    
    sampleLogs.forEach { log ->
        alertLogViewModel.addAlertLog(log, SelectFeatures.meetingProtection)
    }
    onStatusUpdate("Added ${sampleLogs.size} meeting protection logs")
}

private fun addAllSampleLogs(alertLogViewModel: AlertLogViewModel, onStatusUpdate: (String) -> Unit) {
    onStatusUpdate("Adding sample data for all features...")
    
    addSampleCallLogs(alertLogViewModel) { }
    addSampleEmailLogs(alertLogViewModel) { }
    addSampleUrlLogs(alertLogViewModel) { }
    addSampleMeetingLogs(alertLogViewModel) { }
    
    onStatusUpdate("Successfully added sample data for all protection features!")
}

private fun clearAllData(alertLogViewModel: AlertLogViewModel, onStatusUpdate: (String) -> Unit) {
    onStatusUpdate("Clearing all database data...")
    alertLogViewModel.clearAllData()
    onStatusUpdate("All database data has been cleared successfully!")
}
