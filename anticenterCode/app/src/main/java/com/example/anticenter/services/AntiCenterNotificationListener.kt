package com.example.anticenter.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.content.Intent

/**
 * Notification listener capturing incoming notifications.
 * TODO: Forward relevant data to CoreProtectionService.
 */
class AntiCenterNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val n: Notification = sbn.notification
        // First filter out our own foreground service persistent notifications to avoid circular processing
        try {
            val isOurPkg = sbn.packageName == packageName
            val isOngoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val isServiceCategory = n.category == Notification.CATEGORY_SERVICE
            val isProtectChannel = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                n.channelId == "anticenter_protect_channel"
            } else false
            if (isOurPkg && (isProtectChannel || (isOngoing && isServiceCategory))) {
                // Ignore foreground service persistent notifications
                return
            }
        } catch (_: Throwable) {
            // Ignore any exceptions during filtering process, continue normal flow
        }
        val extras = n.extras

        // Extract character-based contents only (safe to pass via Intent)
        fun cs(key: String): String? = try {
            (extras?.getCharSequence(key))?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }

        val title = cs(Notification.EXTRA_TITLE)
        val text = cs(Notification.EXTRA_TEXT)
        val bigText = cs(Notification.EXTRA_BIG_TEXT)
        val subText = cs(Notification.EXTRA_SUB_TEXT)
        val summaryText = cs(Notification.EXTRA_SUMMARY_TEXT)

        val textLines: List<String> = try {
            (extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
                ?.mapNotNull { it?.toString() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }

        // Merge texts into a single payload string, de-duplicated and trimmed
        val parts = mutableListOf<String>()
        fun addPart(s: String?) { if (!s.isNullOrBlank()) parts.add(s.trim()) }
        addPart(title)
        addPart(text)
        addPart(bigText)
        textLines.forEach { addPart(it) }
        addPart(subText)
        addPart(summaryText)

        // Remove consecutive duplicates
        val merged = buildString {
            var last: String? = null
            for (p in parts) {
                if (p != last) {
                    if (isNotEmpty()) append('\n')
                    append(p)
                    last = p
                }
            }
        }

        val channelId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) n.channelId else null
        val intent = Intent(this, CoreProtectionService::class.java).apply {
            action = CoreProtectionService.ACTION_NOTIFICATION_TEXT
            putExtra(CoreProtectionService.EXTRA_SRC_PACKAGE, sbn.packageName)
            putExtra(CoreProtectionService.EXTRA_NOTIFICATION_ID, sbn.id)
            putExtra(CoreProtectionService.EXTRA_POST_TIME, sbn.postTime)
            putExtra(CoreProtectionService.EXTRA_CHANNEL_ID, channelId)
            putExtra(CoreProtectionService.EXTRA_CATEGORY, n.category)
            putExtra(CoreProtectionService.EXTRA_IS_ONGOING, sbn.isOngoing)
            putExtra(CoreProtectionService.EXTRA_TITLE, title)
            putExtra(CoreProtectionService.EXTRA_PAYLOAD_TEXT, merged)
        }

        try {
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.w("AntiCenterNotificationListener", "startService failed to deliver notification text", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { statusBarNotification ->
            try {
                // 检测是否为Zoom会议通知的移除
                if (isZoomMeetingNotificationRemoved(statusBarNotification)) {
                    android.util.Log.d("AntiCenterNotificationListener", 
                        "[ZOOM_REMOVED] Zoom meeting notification removed, stopping protection")
                    
                    // Method 1: Send explicit broadcast to CoreProtectionService
                    val stopIntent = Intent("com.example.anticenter.STOP_ZOOM_PROTECTION")
                    stopIntent.setPackage(packageName)  // Make it explicit
                    stopIntent.putExtra("notificationId", statusBarNotification.id)
                    stopIntent.putExtra("packageName", statusBarNotification.packageName)
                    sendBroadcast(stopIntent)
                    
                    android.util.Log.d("AntiCenterNotificationListener", 
                        "[ZOOM_REMOVED] 📤 Sent STOP_ZOOM_PROTECTION broadcast: id=${statusBarNotification.id}")
                    
                    // Method 2: Also send via service intent as backup (more reliable)
                    val serviceIntent = Intent(this, CoreProtectionService::class.java)
                    serviceIntent.action = "com.example.anticenter.STOP_ZOOM_PROTECTION"
                    serviceIntent.putExtra("notificationId", statusBarNotification.id)
                    serviceIntent.putExtra("packageName", statusBarNotification.packageName)
                    try {
                        startService(serviceIntent)
                        android.util.Log.d("AntiCenterNotificationListener", 
                            "[ZOOM_REMOVED] 📤 Also sent via service intent as backup")
                    } catch (e: Exception) {
                        android.util.Log.e("AntiCenterNotificationListener", 
                            "[ZOOM_REMOVED] Failed to send service intent", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AntiCenterNotificationListener", 
                    "Error handling notification removal", e)
            }
        }
    }
    
    /**
     * 检测移除的通知是否为Zoom会议通知
     */
    private fun isZoomMeetingNotificationRemoved(sbn: StatusBarNotification): Boolean {
        val isZoomApp = sbn.packageName.contains("zoom", ignoreCase = true)
        val notification = sbn.notification
        
        // 检查通知标题和内容
        val title = notification.extras?.getCharSequence("android.title")?.toString() ?: ""
        val text = notification.extras?.getCharSequence("android.text")?.toString() ?: ""
        
        val isZoomTitle = title.contains("Zoom", ignoreCase = true)
        val isMeetingContent = text.contains("Meeting in progress", ignoreCase = true) ||
                              text.contains("会议进行中", ignoreCase = true)
        
        return isZoomApp && isZoomTitle && isMeetingContent
    }
}
