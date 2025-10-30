package com.example.anticenter.services

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AntiCenterNotificationListenerTest {

    private lateinit var application: Application
    private lateinit var shadowApplication: ShadowApplication
    private lateinit var service: AntiCenterNotificationListener

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    shadowApplication = Shadows.shadowOf(application)
    shadowApplication.clearStartedServices()
    shadowApplication.clearBroadcastIntents()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = application.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(
                NotificationChannel("test_channel", "Test", NotificationManager.IMPORTANCE_DEFAULT)
            )
            notificationManager?.createNotificationChannel(
                NotificationChannel("anticenter_protect_channel", "Service", NotificationManager.IMPORTANCE_LOW)
            )
        }

        service = Robolectric.setupService(AntiCenterNotificationListener::class.java)
    }

    @Test
    fun onNotificationPosted_forwardsMergedTextToCoreProtectionService() {
        val notification = NotificationCompat.Builder(application, "test_channel")
            .setContentTitle("Bank Alert")
            .setContentText("Suspicious login detected")
            .setSubText("Account 1234")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Action required immediately")
        notification.extras.putCharSequence(Notification.EXTRA_SUMMARY_TEXT, "Summary details")
        notification.extras.putCharSequenceArray(
            Notification.EXTRA_TEXT_LINES,
            arrayOf("Contact support now")
        )

        val sbn = buildStatusBarNotification(
            packageName = "com.example.partner",
            id = 101,
            notification = notification
        )

        service.onNotificationPosted(sbn)

    val startedIntent = shadowApplication.nextStartedService
        assertThat(startedIntent).isNotNull()
        assertThat(startedIntent!!.component?.className)
            .isEqualTo(CoreProtectionService::class.java.name)
        assertThat(startedIntent.action)
            .isEqualTo(CoreProtectionService.ACTION_NOTIFICATION_TEXT)
        assertThat(startedIntent.getStringExtra(CoreProtectionService.EXTRA_SRC_PACKAGE))
            .isEqualTo("com.example.partner")
        assertThat(startedIntent.getIntExtra(CoreProtectionService.EXTRA_NOTIFICATION_ID, -1))
            .isEqualTo(101)

        val mergedPayload = startedIntent.getStringExtra(CoreProtectionService.EXTRA_PAYLOAD_TEXT)
        assertThat(mergedPayload).isNotNull()
        val payloadLines = mergedPayload!!.lines()
        assertThat(payloadLines).containsAtLeast(
            "Bank Alert",
            "Suspicious login detected",
            "Action required immediately",
            "Contact support now",
            "Account 1234",
            "Summary details"
        )
    }

    @Test
    fun onNotificationPosted_ignoresOwnForegroundServiceNotifications() {
        val notification = NotificationCompat.Builder(application, "anticenter_protect_channel")
            .setContentTitle("Service running")
            .setContentText("Protection active")
            .setCategory(Notification.CATEGORY_SERVICE)
            .build().apply {
                flags = flags or Notification.FLAG_ONGOING_EVENT
            }

        val sbn = buildStatusBarNotification(
            packageName = application.packageName,
            id = 42,
            notification = notification
        )

        service.onNotificationPosted(sbn)

        val startedIntent = shadowApplication.nextStartedService
        assertThat(startedIntent).isNull()
    }

    @Test
    fun onNotificationRemoved_zoomNotificationBroadcastsStopEvent() {
        val notification = NotificationCompat.Builder(application, "test_channel")
            .setContentTitle("Zoom Meeting")
            .setContentText("Meeting in progress")
            .build()

        notification.extras.putCharSequence("android.title", "Zoom Meeting")
        notification.extras.putCharSequence("android.text", "Meeting in progress")

        val sbn = buildStatusBarNotification(
            packageName = "us.zoom.videomeetings",
            id = 777,
            notification = notification
        )

        service.onNotificationRemoved(sbn)

        val broadcastIntent = shadowApplication.broadcastIntents.firstOrNull()
        assertThat(broadcastIntent).isNotNull()
        assertThat(broadcastIntent!!.action)
            .isEqualTo("com.example.anticenter.STOP_ZOOM_PROTECTION")
        assertThat(broadcastIntent.getIntExtra("notificationId", -1)).isEqualTo(777)
        assertThat(broadcastIntent.getStringExtra("packageName"))
            .isEqualTo("us.zoom.videomeetings")

        val serviceIntent = shadowApplication.nextStartedService
        assertThat(serviceIntent).isNotNull()
        assertThat(serviceIntent!!.component?.className)
            .isEqualTo(CoreProtectionService::class.java.name)
        assertThat(serviceIntent.action)
            .isEqualTo("com.example.anticenter.STOP_ZOOM_PROTECTION")
        assertThat(serviceIntent.getIntExtra("notificationId", -1)).isEqualTo(777)
        assertThat(serviceIntent.getStringExtra("packageName"))
            .isEqualTo("us.zoom.videomeetings")
    }

    private fun buildStatusBarNotification(
        packageName: String,
        id: Int,
        notification: Notification
    ): StatusBarNotification {
        return StatusBarNotification(
            packageName,
            packageName,
            id,
            "tag",
            Process.myUid(),
            0,
            0,
            notification,
            Process.myUserHandle(),
            System.currentTimeMillis()
        )
    }
}
