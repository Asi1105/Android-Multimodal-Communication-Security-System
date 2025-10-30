package com.example.anticenter.services

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.anticenter.collectors.EmailCollector
import com.example.anticenter.data.PhishingDataHub
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.*
import java.util.Collections

/**
 * A background service that periodically monitors a Gmail account for new emails.
 * It uses the Gmail API to fetch emails and processes them using [EmailCollector].
 * This service runs as a Foreground Service to ensure it's not killed by the system
 * when the app is in the background.
 */
class GmailMonitorService : Service() {

    companion object {
        private const val TAG = "GmailMonitorService"
        private const val NOTIFICATION_ID = 2
        private const val GMAIL_SERVICE_CHANNEL_ID = "gmail_monitor_service_channel"
        private const val ACTION_STOP = "com.example.multimodalmonitoringintegration.services.ACTION_STOP_GMAIL"

        private const val POLLING_INTERVAL_MS = 5 * 1000L
        const val EXTRA_ACCOUNT_NAME = "accountName"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var emailCollector: EmailCollector
    private var gmailService: Gmail? = null
    private var lastQueryTimestampMs: Long = 0L
    private var accountName: String? = null

    override fun onCreate() {
        super.onCreate()
        emailCollector = EmailCollector(applicationContext, PhishingDataHub)
        Log.i(TAG, "GmailMonitorService created.")
        lastQueryTimestampMs = getSharedPreferences("GmailMonitorPrefs", MODE_PRIVATE)
            .getLong("lastQueryTimestampMs", System.currentTimeMillis())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle the stop action from the notification
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Received stop action from notification.")
            stopServiceGracefully()
            return START_NOT_STICKY
        }

        Log.i(TAG, "GmailMonitorService started.")
        // Use a temporary "Initializing..." notification to quickly start the foreground service
        try {
            val notification = buildNotification("Initializing...")
            startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "Started foreground service with notification ID: $NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        accountName = intent?.getStringExtra(EXTRA_ACCOUNT_NAME)
        if (accountName == null) {
            Log.e(TAG, "Account name not provided to service. Stopping.")
            stopServiceGracefully()
            return START_NOT_STICKY
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)?.takeIf { it.email == accountName }
        if (account == null || !account.grantedScopes.contains(Scope(GmailScopes.GMAIL_READONLY))) {
            Log.e(TAG, "Google account not found or Gmail scope not granted for $accountName. Stopping service.")
            stopServiceGracefully()
            return START_NOT_STICKY
        }

        initializeGmailService(account)

        // Start the polling loop
        serviceScope.launch {
            while (isActive) {
                updateNotification("Polling for new emails...")
                Log.i(TAG, "Polling: Fetching new emails...")
                fetchNewEmails()
                delay(POLLING_INTERVAL_MS)
            }
        }

        // If the service is terminated, try to recreate it.
        return START_STICKY
    }

    private fun initializeGmailService(account: GoogleSignInAccount) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                applicationContext,
                Collections.singleton(GmailScopes.GMAIL_READONLY)
            )
            credential.selectedAccount = account.account
            gmailService = Gmail.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName(applicationInfo.loadLabel(packageManager).toString()).build()
            Log.i(TAG, "Gmail service initialized for ${account.email}.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Gmail service.", e)
            stopServiceGracefully()
        }
    }

    private suspend fun fetchNewEmails() {
        if (gmailService == null) {
            Log.w(TAG, "Gmail service not initialized. Skipping email fetch.")
            updateNotification("Service initialization failed, please retry.")
            val account = GoogleSignIn.getLastSignedInAccount(this)?.takeIf { it.email == accountName }
            if (account != null) initializeGmailService(account) else stopServiceGracefully()
            return
        }

        try {
            val query = "after:${lastQueryTimestampMs / 1000}"
            Log.d(TAG, "Gmail API query: $query")

            val response = gmailService?.users()?.messages()?.list("me")?.setQ(query)?.execute()
            val messages = response?.messages ?: emptyList()

            if (messages.isEmpty()) {
                updateNotification("No new emails found. Waiting for next poll...")
                Log.i(TAG, "No new messages found since last poll.")
            } else {
                updateNotification("Found ${messages.size} new email(s), processing...")
                Log.i(TAG, "Found ${messages.size} new message(s).")
                messages.forEach { messageSummary ->
                    try {
                        val fullMessage = gmailService?.users()?.messages()?.get("me", messageSummary.id)?.execute()
                        if (fullMessage != null) {
                            emailCollector.processRetrievedEmail(fullMessage)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching full message ${messageSummary.id}.", e)
                    }
                }
            }
            lastQueryTimestampMs = System.currentTimeMillis()
            getSharedPreferences("GmailMonitorPrefs", MODE_PRIVATE).edit()
                .putLong("lastQueryTimestampMs", lastQueryTimestampMs).apply()

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching emails.", e)
            updateNotification("Error fetching emails. Please check network connection.")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GMAIL_SERVICE_CHANNEL_ID,
                "Gmail Monitoring Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows status of Gmail monitoring service"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel created: $GMAIL_SERVICE_CHANNEL_ID")
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()

        // Intent for the "Stop" button
        val stopIntent = Intent(this, GmailMonitorService::class.java).apply { action = ACTION_STOP }

        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, GMAIL_SERVICE_CHANNEL_ID)
            .setContentTitle("Gmail Monitoring Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email) // 使用系统图标确保兼容性
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Subsequent updates will not make a sound
            .addAction(R.drawable.ic_media_pause, "Stop", stopPi) // Add stop button
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 确保通知显示
            .setCategory(NotificationCompat.CATEGORY_SERVICE) // 标记为服务类型
            .build()
        
        Log.i(TAG, "Built notification: $text")
        return notification
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = buildNotification(text)
            nm.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Updated notification: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun stopServiceGracefully() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.i(TAG, "GmailMonitorService destroyed.")
        getSharedPreferences("GmailMonitorPrefs", MODE_PRIVATE).edit()
            .putLong("lastQueryTimestampMs", lastQueryTimestampMs).apply()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
