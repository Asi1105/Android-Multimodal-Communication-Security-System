package com.example.anticenter.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.content.ComponentName
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.anticenter.R
import com.example.anticenter.dataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.example.anticenter.database.AntiCenterRepository
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.PhishingDataHub
import com.example.anticenter.data.PhishingData
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.data.ContentLogItem
import com.example.anticenter.services.BCRMonitorCollector
import com.example.anticenter.collectors.ZoomCollector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collect

/**
 * Core foreground orchestrator service.
 * TODO: integrate notification / accessibility callbacks and decision logic.
 */
class CoreProtectionService : Service() {
    private var permissionChangeReceiver: BroadcastReceiver? = null
    private var zoomStopReceiver: BroadcastReceiver? = null
    private var mediaProjectionReceiver: BroadcastReceiver? = null
    private var mediaProjectionResultReceiver: BroadcastReceiver? = null
    private var appOpsManager: AppOpsManager? = null
    private var appOpsListener: AppOpsManager.OnOpChangedListener? = null
    private var settingsObserver: ContentObserver? = null
    // Prevent duplicate overlay test popup triggers
    @Volatile private var overlayTestTriggered: Boolean = false

    // Latest snapshot of permission and capability status for comparison of changes
    private var lastSnapshot: PermissionSnapshot? = null

    // For asynchronous DataStore writes
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    //MARK: Async notification queue (Channel) for NotificationTextEvent
    // In-memory channel for notification text events
    private val notificationChannel = kotlinx.coroutines.channels.Channel<NotificationTextEvent>(capacity = kotlinx.coroutines.channels.Channel.BUFFERED)
    
    // Repository for database operations
    private lateinit var repository: AntiCenterRepository

    // Telephony monitoring for incoming call detection
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var currentIncomingNumber: String? = null
    private val cybertraceRiskClient = CybertraceRiskClient()
    private var currentIncomingRisk: CybertraceRiskClient.Result? = null
    private var isCallProtectionActive: Boolean = false
    private var callProtectionState: CallProtectionState = CallProtectionState.INACTIVE
    private var lastProcessedCallNotificationId: Int = -1
    private var isZoomProtectionActive: Boolean = false
    private var currentZoomMeetingId: String? = null
    private var lastProcessedZoomNotificationId: Int = -1
    
    // MediaProjection权限管理
    private var pendingZoomMeetingId: String? = null
    private var isWaitingForMediaProjection: Boolean = false
    private var mediaProjectionCheckJob: kotlinx.coroutines.Job? = null
    
    // BCR 监控器实例
    private var bcrMonitor: BCRMonitorCollector? = null
    
    enum class CallProtectionState {
        INACTIVE,     // No protection state
        INCOMING,     // Incoming call detection
        IN_CALL,      // In call
        ENDING        // Call ending
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("CoreProtectionService", "onCreate")

        // Initialize repository
        repository = AntiCenterRepository.getInstance(applicationContext)

        // Initialize ZoomCollector singleton
        initializeZoomCollector()

        // Register package change broadcast: when user changes app permissions in settings, 
        // system usually sends PACKAGE_CHANGED to this package
        registerPackageChangeReceiver()

        // Register Zoom stop protection broadcast receiver
        registerZoomStopReceiver()

        // Register MediaProjection permission result receiver
        registerMediaProjectionReceiver()

        // Monitor key AppOps (notifications, overlay, recording, etc.) changes
        startWatchingAppOps()

    // Observe system UI related settings (accessibility, notification listener) changes
    registerSettingsObservers()

        // Start event consumption coroutine
        startNotificationEventConsumer()

        // Start PhishingDataHub subscription
        startPhishingDataConsumer()

        // Register phone state listener
        registerPhoneStateListener()

        // Take a snapshot log at startup (full snapshot)
        checkAndLogPermissionStates(reason = "service_onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("CoreProtectionService", "onStartCommand flags=$flags startId=$startId action=${intent?.action}")
        try {
            // Enter foreground state as soon as possible to meet FGS time limit requirements
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (SDK 34): Check RECORD_AUDIO permission before using microphone type
                val hasRecordAudio = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                // Even if RECORD_AUDIO appears granted, emulator/device may still block microphone FGS type.
                // To avoid SecurityException during startForeground, use dataSync only unless microphone is strictly required.
                val foregroundType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (!hasRecordAudio) {
                    android.util.Log.w(
                        "CoreProtectionService",
                        "⚠️ RECORD_AUDIO not granted, starting with dataSync type only (microphone features disabled)"
                    )
                } else {
                    android.util.Log.d(
                        "CoreProtectionService",
                        "✅ RECORD_AUDIO granted but using dataSync type to stay compliant with FGS restrictions"
                    )
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildPersistentNotification(),
                    foregroundType
                )
            } else {
                startForeground(NOTIFICATION_ID, buildPersistentNotification())
            }
            android.util.Log.d("CoreProtectionService", "✅ startForeground called successfully")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "❌ startForeground failed", e)
            stopSelf() // Stop service if can't enter foreground
        }
        
        // Handle different actions
        when (intent?.action) {
            ACTION_NOTIFICATION_TEXT -> {
                // Process notification text events from listener service
                handleNotificationTextIntent(intent)
            }
            "com.example.anticenter.STOP_ZOOM_PROTECTION" -> {
                // Handle Zoom protection stop request
                val notificationId = intent.getIntExtra("notificationId", -1)
                val packageName = intent.getStringExtra("packageName") ?: ""
                
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_STOP_SERVICE] 📥 Received STOP_ZOOM_PROTECTION via service intent, " +
                    "notificationId=$notificationId, package=$packageName")
                
                // 验证是否为我们追踪的Zoom通知
                if (notificationId == lastProcessedZoomNotificationId || 
                    (isZoomProtectionActive && packageName.contains("zoom", ignoreCase = true))) {
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_STOP_SERVICE] ✅ Validated - stopping Zoom protection")
                    stopZoomProtection()
                } else {
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_STOP_SERVICE] ❌ Validation failed - lastProcessed=$lastProcessedZoomNotificationId, " +
                        "protectionActive=$isZoomProtectionActive")
                }
            }
        }
        
        // After entering foreground, try to trigger overlay test popup once (only once)
        //maybeTriggerOverlayTest()
        // Other events can be dispatched here
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Initialize ZoomCollector singleton instance
     */
    private fun initializeZoomCollector() {
        try {
            // Create ZoomCollector instance with applicationContext and PhishingDataHub
            val zoomCollector = ZoomCollector(applicationContext, PhishingDataHub)
            android.util.Log.d("CoreProtectionService", "[ZOOM_COLLECTOR] ZoomCollector singleton initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "[ZOOM_COLLECTOR] Failed to initialize ZoomCollector", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("CoreProtectionService", "onDestroy")
        // Unregister broadcast receivers
        try {
            permissionChangeReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "unregisterReceiver for permission changes failed", e)
        } finally {
            permissionChangeReceiver = null
        }
        
        try {
            zoomStopReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "unregisterReceiver for zoom stop failed", e)
        } finally {
            zoomStopReceiver = null
        }
        
        try {
            mediaProjectionReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "unregisterReceiver for media projection failed", e)
        } finally {
            mediaProjectionReceiver = null
        }
        // Stop AppOps monitoring
        try {
            appOpsListener?.let { listener ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    appOpsManager?.stopWatchingMode(listener)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "stopWatchingMode failed", e)
        } finally {
            appOpsListener = null
            appOpsManager = null
        }

        // Unregister Settings observer
        try {
            settingsObserver?.let { contentResolver.unregisterContentObserver(it) }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "unregisterContentObserver failed", e)
        } finally {
            settingsObserver = null
        }

        // Unregister phone state listener
        try {
            telephonyCallback?.let { callback ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyManager?.unregisterTelephonyCallback(callback)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "unregisterTelephonyCallback failed", e)
        } finally {
            telephonyCallback = null
            telephonyManager = null
        }

        // Stop periodic tasks
        // Periodic checks have been removed, no need to clean up callbacks

        // Close event queue
        try { notificationChannel.close() } catch (_: Exception) {}
        // Cancel coroutine scope
        try { serviceScope.cancel() } catch (_: Exception) {}
    }

    private fun buildPersistentNotification(): Notification {
        val channelId = "anticenter_protect_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "AntiCenter Protection",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Core protection service running" }
                )
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Protection Active")
            .setContentText("Real-time anti-fraud monitoring enabled")
            .setOngoing(true)
            .build()
    }

    private fun registerPackageChangeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val affectedPkg = intent.data?.schemeSpecificPart
                if (affectedPkg == packageName) {
                    android.util.Log.d(
                        "CoreProtectionService",
                        "Package change action=$action for=$affectedPkg"
                    )
                    checkAndLogPermissionStates(reason = "broadcast_$action")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            permissionChangeReceiver = receiver
            android.util.Log.d("CoreProtectionService", "PackageChangeReceiver registered")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "registerReceiver failed", e)
        }
    }

    private fun registerZoomStopReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.example.anticenter.STOP_ZOOM_PROTECTION" -> {
                        val notificationId = intent.getIntExtra("notificationId", -1)
                        val packageName = intent.getStringExtra("packageName") ?: ""
                        
                        android.util.Log.d("CoreProtectionService", 
                            "[ZOOM_STOP_BROADCAST] Received stop signal from notification removal, " +
                            "notificationId=$notificationId, package=$packageName")
                        
                        // 验证是否为我们追踪的Zoom通知
                        if (notificationId == lastProcessedZoomNotificationId || 
                            (isZoomProtectionActive && packageName.contains("zoom", ignoreCase = true))) {
                            android.util.Log.d("CoreProtectionService", 
                                "[ZOOM_MEETING_DETECTION] 📱 Zoom meeting notification removed - triggering meeting end detection")
                            stopZoomProtection()
                        } else {
                            android.util.Log.d("CoreProtectionService", 
                                "[ZOOM_STOP_BROADCAST] Ignoring stop signal - not matching our tracked notification")
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("com.example.anticenter.STOP_ZOOM_PROTECTION")
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            zoomStopReceiver = receiver
            android.util.Log.d("CoreProtectionService", "ZoomStopReceiver registered")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "registerZoomStopReceiver failed", e)
        }
    }

    private fun registerMediaProjectionReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.example.anticenter.MEDIA_PROJECTION_GRANTED" -> {
                        val meetingId = intent.getStringExtra("meeting_id") ?: ""
                        val consent = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra("consent", Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra("consent")
                        }
                        
                        android.util.Log.d("CoreProtectionService", 
                            "[MEDIA_PROJECTION] 🎉 Permission granted for meeting: $meetingId")
                        
                        if (consent != null) {
                            android.util.Log.d("CoreProtectionService", 
                                "[MEDIA_PROJECTION] ✅ Consent received, starting ZoomCapService")
                            startZoomCapServiceWithConsent(meetingId, consent)
                        } else {
                            android.util.Log.e("CoreProtectionService", 
                                "[MEDIA_PROJECTION] ❌ Consent is null, cannot start ZoomCapService")
                        }
                    }
                    "com.example.anticenter.MEDIA_PROJECTION_DENIED" -> {
                        val meetingId = intent.getStringExtra("meeting_id") ?: ""
                        android.util.Log.w("CoreProtectionService", 
                            "[MEDIA_PROJECTION] Permission denied for meeting: $meetingId - stopping Zoom protection")
                        
                        // 用户拒绝录制权限，关闭这轮meeting的防护服务
                        handleMediaProjectionDenied(meetingId)
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction("com.example.anticenter.MEDIA_PROJECTION_GRANTED")
            addAction("com.example.anticenter.MEDIA_PROJECTION_DENIED")
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            mediaProjectionReceiver = receiver
            android.util.Log.d("CoreProtectionService", "MediaProjectionReceiver registered")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "registerMediaProjectionReceiver failed", e)
        }
    }

    private fun startZoomCapServiceWithConsent(meetingId: String, consent: Intent) {
        serviceScope.launch {
            try {
                val zoomCollector = ZoomCollector.getInstance()
                if (zoomCollector != null) {
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] Starting ZoomCapService with MediaProjection consent for meeting: $meetingId")
                    
                    val started = zoomCollector.startWithConsent(consent)
                    if (started) {
                        android.util.Log.d("CoreProtectionService", 
                            "[ZOOM_PROTECTION] ✅ ZoomCapService started successfully for meeting: $meetingId")
                    } else {
                        android.util.Log.e("CoreProtectionService", 
                            "[ZOOM_PROTECTION] ❌ Failed to start ZoomCapService for meeting: $meetingId")
                        isZoomProtectionActive = false
                    }
                } else {
                    android.util.Log.e("CoreProtectionService", 
                        "[ZOOM_PROTECTION] ZoomCollector instance not found")
                    isZoomProtectionActive = false
                }
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", 
                    "[ZOOM_PROTECTION] Error starting ZoomCapService with consent", e)
                isZoomProtectionActive = false
            }
        }
    }

    private fun handleMediaProjectionDenied(meetingId: String) {
        serviceScope.launch {
            try {
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] 🚫 User denied screen recording permission for meeting: $meetingId")
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] 🛑 Stopping Zoom protection due to permission denial")
                
                // 重置保护状态
                isZoomProtectionActive = false
                
                // 清理会议信息
                if (currentZoomMeetingId == meetingId) {
                    currentZoomMeetingId = null
                    lastProcessedZoomNotificationId = -1
                }
                
                // 确保没有残留的服务在运行
                val zoomCollector = ZoomCollector.getInstance()
                if (zoomCollector != null && zoomCollector.isRunning()) {
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] Stopping any running ZoomCapService due to permission denial")
                    zoomCollector.stop()
                }
                
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] ✅ Meeting protection cleanup complete for denied permission")
                
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", 
                    "[ZOOM_PROTECTION] Error handling MediaProjection denial", e)
            }
        }
    }

    private fun startWatchingAppOps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        try {
            val mgr = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOpsManager = mgr
            val listener = AppOpsManager.OnOpChangedListener { op, pkg ->
                if (pkg == packageName) {
                    android.util.Log.d("CoreProtectionService", "AppOp changed: $op for $pkg")
                    checkAndLogPermissionStates(reason = "appops_$op")
                }
            }
            appOpsListener = listener

            
            val opsToWatch = mutableListOf(
                "android:post_notification",
                "android:system_alert_window",
                "android:record_audio",
                "android:read_sms",
                "android:read_contacts"
            )

            opsToWatch.distinct().forEach { op ->
                try {
                    mgr.startWatchingMode(op, packageName, listener)
                    android.util.Log.d("CoreProtectionService", "Watching AppOp: $op")
                } catch (e: Exception) {
                    android.util.Log.w("CoreProtectionService", "startWatchingMode failed for $op", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "startWatchingAppOps failed", e)
        }
    }

    private fun checkAndLogPermissionStates(reason: String) {
        fun granted(p: String): Boolean =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

        // Runtime permission and related capability collection
        val snapshot = PermissionSnapshot(
            readPhoneState = granted(Manifest.permission.READ_PHONE_STATE),
            readPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                granted(Manifest.permission.READ_PHONE_NUMBERS) else null,
            readContacts = granted(Manifest.permission.READ_CONTACTS),
            readSms = granted(Manifest.permission.READ_SMS),
            receiveSms = granted(Manifest.permission.RECEIVE_SMS),
            recordAudio = granted(Manifest.permission.RECORD_AUDIO),
            notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                granted(Manifest.permission.POST_NOTIFICATIONS) else true,
            canOverlay = Settings.canDrawOverlays(this),
            accEnabled = isOurAccessibilityServiceEnabled(),
            notiListenerEnabled = isOurNotificationListenerEnabled()
        )

        // Prioritize using the last one in memory; if empty, try loading from persistence (to handle process kill scenarios)
        val previous = lastSnapshot ?: loadPersistedSnapshot()
        if (previous == null) {
            // First time: print full snapshot
            android.util.Log.d("CoreProtectionService", buildFullSnapshotLog(reason, snapshot))
        } else {
            // Compare differences and only print changed items
            val diffs = buildDiffLog(previous, snapshot)
            if (diffs.isNotEmpty()) {
                android.util.Log.d(
                    "CoreProtectionService",
                    "[perm-change] reason=$reason\n$diffs"
                )
                // Set preferences for revoked capabilities directly to false
                updatePreferencesOnRevocation(previous, snapshot)
            } else {
                android.util.Log.d(
                    "CoreProtectionService",
                    "[perm-change] reason=$reason no changes"
                )
            }
        }

        // Update snapshot
        lastSnapshot = snapshot
        persistSnapshot(snapshot)

        // Notify other modules in the app (such as UI) that permission status can be refreshed
        try {
            val intent = Intent("com.example.anticenter.PERMISSIONS_UPDATED")
            sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "send permissions broadcast failed", e)
        }
    }

    private fun buildFullSnapshotLog(reason: String, s: PermissionSnapshot): String = buildString {
        append("[perm-scan] reason=").append(reason).append('\n')
        append("READ_PHONE_STATE=").append(s.readPhoneState).append('\n')
        append("READ_PHONE_NUMBERS=").append(s.readPhoneNumbers.toLog()).append('\n')
        append("READ_CONTACTS=").append(s.readContacts).append('\n')
        append("READ_SMS=").append(s.readSms).append('\n')
        append("RECEIVE_SMS=").append(s.receiveSms).append('\n')
        append("RECORD_AUDIO=").append(s.recordAudio).append('\n')
        append("NOTIFICATIONS_ENABLED=").append(s.notificationsEnabled).append('\n')
        append("POST_NOTIFICATIONS_GRANTED=").append(s.postNotificationsGranted.toLog()).append('\n')
        append("CAN_OVERLAY=").append(s.canOverlay).append('\n')
        append("ACCESSIBILITY_ENABLED=").append(s.accEnabled).append('\n')
        append("NOTI_LISTENER_ENABLED=").append(s.notiListenerEnabled)
    }

    private fun buildDiffLog(old: PermissionSnapshot, s: PermissionSnapshot): String = buildString {
        fun line(name: String, before: Boolean?, after: Boolean?) {
            if (before != after) append("- ").append(name)
                .append(": ")
                .append(before.toLog())
                .append(" -> ")
                .append(after.toLog())
                .append('\n')
        }
        line("READ_PHONE_STATE", old.readPhoneState, s.readPhoneState)
        line("READ_PHONE_NUMBERS", old.readPhoneNumbers, s.readPhoneNumbers)
        line("READ_CONTACTS", old.readContacts, s.readContacts)
        line("READ_SMS", old.readSms, s.readSms)
        line("RECEIVE_SMS", old.receiveSms, s.receiveSms)
        line("RECORD_AUDIO", old.recordAudio, s.recordAudio)
        line("NOTIFICATIONS_ENABLED", old.notificationsEnabled, s.notificationsEnabled)
        line("POST_NOTIFICATIONS_GRANTED", old.postNotificationsGranted, s.postNotificationsGranted)
        line("CAN_OVERLAY", old.canOverlay, s.canOverlay)
        line("ACCESSIBILITY_ENABLED", old.accEnabled, s.accEnabled)
        line("NOTI_LISTENER_ENABLED", old.notiListenerEnabled, s.notiListenerEnabled)
    }.trimEnd()

    private fun Boolean?.toLog(): String = when (this) {
        true -> "true"
        false -> "false"
        null -> "n/a"
    }

    private fun registerSettingsObservers() {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val key = when (uri?.toString()) {
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).toString() -> "enabled_accessibility_services"
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED).toString() -> "accessibility_enabled"
                    Settings.Secure.getUriFor("enabled_notification_listeners").toString() -> "enabled_notification_listeners"
                    else -> uri?.toString() ?: "unknown"
                }
                android.util.Log.d("CoreProtectionService", "Settings changed: $key")
                checkAndLogPermissionStates(reason = "settings_$key")
            }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor("enabled_notification_listeners"),
                false,
                observer
            )
            settingsObserver = observer
            android.util.Log.d("CoreProtectionService", "Settings observers registered")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "registerSettingsObservers failed", e)
        }
    }

    private fun isOurAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!enabled) return false
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val myService = ComponentName(this, com.example.anticenter.services.AntiCenterAccessibilityService::class.java)
                .flattenToString()
            enabledServices.split(":").any { it.equals(myService, ignoreCase = true) }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "isOurAccessibilityServiceEnabled error", e)
            false
        }
    }

    private fun isOurNotificationListenerEnabled(): Boolean {
        return try {
            val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?: return false
            // System records ComponentName flattened strings, including package name
            listeners.split(":").any { it.contains(packageName, ignoreCase = true) }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "isOurNotificationListenerEnabled error", e)
            false
        }
    }

    // ---- Notification text event protocol & constants ----
    companion object Protocol {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_NOTIFICATION_TEXT = "com.example.anticenter.ACTION_NOTIFICATION_TEXT"
        const val EXTRA_SRC_PACKAGE = "srcPackage"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_POST_TIME = "postTime"
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_IS_ONGOING = "isOngoing"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PAYLOAD_TEXT = "payloadText"
        
        // MediaProjection权限请求相关常量
        private const val MEDIA_PROJECTION_REQUEST_CODE = 2001
        private const val MEDIA_PROJECTION_NOTIFICATION_ID = 2002
        private const val MEDIA_PROJECTION_CHANNEL_ID = "media_projection_permission"
        private const val CYBERTRACE_TIMEOUT_MS = 3_500L
    }

    data class NotificationTextEvent(
        val srcPackage: String,
        val notificationId: Int,
        val postTime: Long,
        val channelId: String?,
        val category: String?,
        val isOngoing: Boolean,
        val title: String?,
        val payloadText: String,
    )

    private fun handleNotificationTextIntent(intent: Intent) {
        val srcPackage = intent.getStringExtra(EXTRA_SRC_PACKAGE) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val postTime = intent.getLongExtra(EXTRA_POST_TIME, 0L)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val category = intent.getStringExtra(EXTRA_CATEGORY)
        val isOngoing = intent.getBooleanExtra(EXTRA_IS_ONGOING, false)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val payloadText = intent.getStringExtra(EXTRA_PAYLOAD_TEXT) ?: ""

        if (notificationId < 0) return

        val event = NotificationTextEvent(
            srcPackage = srcPackage,
            notificationId = notificationId,
            postTime = postTime,
            channelId = channelId,
            category = category,
            isOngoing = isOngoing,
            title = title,
            payloadText = payloadText,
        )

        serviceScope.launch {
            try {
                notificationChannel.send(event)
            } catch (e: Exception) {
                android.util.Log.w("CoreProtectionService", "Failed to enqueue NotificationTextEvent", e)
            }
        }
    }
    //MARK: Async notification event consumer
    private fun startNotificationEventConsumer() {
        serviceScope.launch {
            for (event in notificationChannel) {
                try {
                    android.util.Log.d(
                        "CoreProtectionService",
                        "[notif-event] pkg=${event.srcPackage} id=${event.notificationId} title=${event.title}\n${event.payloadText.take(500)}"
                    )
                    
                    // Detect if it's a dialer incoming call notification
                    if (isIncomingCallNotification(event)) {
                        // Filter duplicate incoming call notifications
                        if (shouldProcessCallNotification(event)) {
                            handleIncomingCallFromNotification(event)
                        } else {
                            android.util.Log.d("CoreProtectionService", "[CALL_FILTER] Skipping duplicate call notification id=${event.notificationId}")
                        }
                    }
                    
                    // Detect if it's a Zoom meeting notification
                    if (isZoomMeetingNotification(event)) {
                        // Filter duplicate Zoom meeting notifications
                        if (shouldProcessZoomNotification(event)) {
                            handleZoomMeetingFromNotification(event)
                        } else {
                            android.util.Log.d("CoreProtectionService", "[ZOOM_FILTER] Skipping duplicate Zoom notification id=${event.notificationId}")
                        }
                    }
                    
                    // [Protection Feature Trigger Point] Other notification event handling - Add risk notification detection, fraud SMS recognition and other protection logic here
                    
                } catch (e: Exception) {
                    android.util.Log.w("CoreProtectionService", "Notification event handling failed", e)
                }
            }
        }
    }
    //TODO: Should align with actual datahub structure
    //MARK: PhishingDataHub consumer
    private fun startPhishingDataConsumer() {
        serviceScope.launch {
            try {
                PhishingDataHub.phishingDataFlow.collect { data ->
                    try {
                        android.util.Log.d("CoreProtectionService", "[PHISHING_DATA] Received data: ${data::class.simpleName}")
                        handlePhishingDataEvent(data)
                    } catch (e: Exception) {
                        android.util.Log.w("CoreProtectionService", "[PHISHING_DATA] Failed to handle phishing data", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", "[PHISHING_DATA] Failed to subscribe to PhishingDataHub", e)
            }
        }
    }

    private suspend fun handlePhishingDataEvent(data: Any) {
        when (data) {
            is PhishingData -> {
                android.util.Log.d("CoreProtectionService", "[PHISHING_DATA] Processing PhishingData: type=${data.dataType}")
                
                // Save content to content log table
                val contentLogItem = ContentLogItem(
                    type = data.dataType,
                    timestamp = System.currentTimeMillis(),
                    content = data.content
                )
                
                try {
                    val result = repository.addContentLogItem(contentLogItem)
                    if (result.isSuccess) {
                        android.util.Log.d("CoreProtectionService", "[CONTENT_LOG] Content saved to database: type=${data.dataType}, id=${result.getOrNull()}")
                    } else {
                        android.util.Log.w("CoreProtectionService", "[CONTENT_LOG] Failed to save content to database: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CoreProtectionService", "[CONTENT_LOG] Error saving content to database", e)
                }
                
                // Convert PhishingData to AlertLogItem based on data type
                val alertItem = convertPhishingDataToAlertItem(data)
                if (alertItem != null) {
                    // Save to database via repository
                    val featureType = mapDataTypeToFeature(data.dataType)
                    saveAlertToDatabase(alertItem, featureType)
                    
                    // Trigger overlay banner for all threats (since reaching here means threat detected)
                    triggerThreatOverlayBanner(data)
                } else {
                    android.util.Log.w("CoreProtectionService", "[PHISHING_DATA] Failed to convert PhishingData to AlertLogItem")
                }
            }
            else -> {
                android.util.Log.d("CoreProtectionService", "[PHISHING_DATA] Unknown data type: ${data::class.simpleName}")
            }
        }
    }

    /**
     * 将 PhishingData 转换为 AlertLogItem
     * 已重构为使用 PhishingDataConverter 工具类
     */
    private fun convertPhishingDataToAlertItem(phishingData: PhishingData): AlertLogItem? {
        return PhishingDataConverter.convertToAlertItem(phishingData)
    }

    /**
     * 将数据类型映射到对应的功能模块
     * 已重构为使用 PhishingDataConverter 工具类
     */
    private fun mapDataTypeToFeature(dataType: String): SelectFeatures {
        return PhishingDataConverter.mapDataTypeToFeature(dataType)
    }

    private suspend fun saveAlertToDatabase(alertItem: AlertLogItem, featureType: SelectFeatures) {
        try {
            val result = repository.addAlertLogItem(alertItem, featureType)
            if (result.isSuccess) {
                android.util.Log.d("CoreProtectionService", "[PHISHING_DATA] Alert saved to database: type=${alertItem.type}, source=${alertItem.source}")
            } else {
                android.util.Log.w("CoreProtectionService", "[PHISHING_DATA] Failed to save alert to database: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "[PHISHING_DATA] Error saving alert to database", e)
        }
    }



    private fun triggerThreatOverlayBanner(phishingData: PhishingData) {
        // TODO: Implement overlay banner trigger logic
        // This is a placeholder for future overlay banner integration
        try {
            val title = when (phishingData.dataType.lowercase()) {
                "email" -> "Phishing Email Detected"
                "zoom" -> "Suspicious Meeting Activity"
                "phonecall" -> "Suspicious Call Activity"
                else -> "Phishing Attack Detected"
            }
            
            val message = when (phishingData.dataType.lowercase()) {
                "email" -> {
                    val sender = phishingData.metadata["sender"] ?: "Unknown"
                    val subject = phishingData.metadata["subject"] ?: "No Subject"
                    "Phishing email from $sender\nSubject: $subject"
                }
                "zoom" -> {
                    // 从 metadata 中获取检测类型和检测器信息
                    val mediaType = phishingData.metadata["mediaType"] ?: ""
                    val detector = phishingData.metadata["detector"] ?: ""
                    val resultStatus = phishingData.metadata["resultStatus"] ?: ""
                    val fileName = phishingData.metadata["fileName"] ?: ""
                    
                    when (mediaType.uppercase()) {
                        "VIDEO" -> {
                            "Deepfake video detected in meeting\nFile: $fileName"
                        }
                        "AUDIO" -> {
                            "Deepfake audio detected in meeting\nFile: $fileName"
                        }
                        "AUDIO_PHISHING" -> {
                            "Voice phishing detected in meeting audio\nFile: $fileName"
                        }
                        "IMAGE" -> {
                            "Deepfake image detected in meeting\nFile: $fileName"
                        }
                        else -> "Suspicious meeting activity detected"
                    }
                }
                "phonecall" -> {
                    val source = phishingData.metadata["source"] ?: "Unknown Number"
                    "Suspicious call from $source"
                }
                else -> "A security threat has been detected"
            }
            
            android.util.Log.d("CoreProtectionService", "[OVERLAY_BANNER] Should trigger banner: title=$title, message=$message")
            
            // TODO: Uncomment when ready to integrate with OverlayBannerService
            /*
            val intent = Intent(this, OverlayBannerService::class.java).apply {
                action = OverlayBannerService.ACTION_SHOW
                putExtra(OverlayBannerService.EXTRA_TITLE, title)
                putExtra(OverlayBannerService.EXTRA_MESSAGE, message)
                putExtra(OverlayBannerService.EXTRA_DELAY_MS, 1000L) // Show after 1 second
            }
            startService(intent)
            */

            val intent = Intent(this, OverlayBannerService::class.java).apply {
                action = OverlayBannerService.ACTION_SHOW
                putExtra(OverlayBannerService.EXTRA_TITLE, title)
                putExtra(OverlayBannerService.EXTRA_MESSAGE, message)
                putExtra(OverlayBannerService.EXTRA_DELAY_MS, 1000L) // Show after 1 second
            }
            startService(intent)

            
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "[OVERLAY_BANNER] Failed to trigger overlay banner", e)
        }
    }

    private fun maybeShowCybertraceRiskBanner(phoneNumber: String, risk: CybertraceRiskClient.Result) {
        val level = risk.riskLevel
        if (level == CybertraceRiskClient.RiskLevel.LOW || level == CybertraceRiskClient.RiskLevel.UNKNOWN) {
            android.util.Log.d("CoreProtectionService", "[CYBERTRACE] Risk level ${'$'}level, banner suppressed")
            return
        }

        try {
            val scoreText = risk.riskPercent?.let { "$it%" } ?: "unknown"
            val statement = risk.riskStatement?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            } ?: level.name.lowercase(Locale.ROOT)

            val detailParts = buildList {
                risk.reportedCount?.takeIf { it > 0 }?.let { count ->
                    add("Reported $count time${if (count > 1) "s" else ""}.")
                }
                risk.searchedCount?.takeIf { it > 0 }?.let { count ->
                    add("Searched $count time${if (count > 1) "s" else ""}.")
                }
            }

            val detail = detailParts.joinToString(" ")

            val title = "Incoming call risk alert"
            val message = buildString {
                append("Number ").append(phoneNumber)
                append(" flagged ").append(statement)
                append(" (score ").append(scoreText).append(").")
                if (detail.isNotEmpty()) {
                    append(' ').append(detail)
                }
            }

            val intent = Intent(this, OverlayBannerService::class.java).apply {
                action = OverlayBannerService.ACTION_SHOW
                putExtra(OverlayBannerService.EXTRA_TITLE, title)
                putExtra(OverlayBannerService.EXTRA_MESSAGE, message)
                putExtra(OverlayBannerService.EXTRA_DELAY_MS, 500L)
            }
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "[CYBERTRACE] Failed to show risk banner", e)
        }
    }
    //MARK: Overlay test trigger (for debugging) 
    private fun maybeTriggerOverlayTest() {
        if (overlayTestTriggered) return
        overlayTestTriggered = true
        try {
            // If overlay permission is missing, don't trigger (avoid error log interference)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                android.util.Log.w("CoreProtectionService", "Overlay permission not granted; skip test trigger")
                return
            }
            val intent = Intent(this, OverlayBannerService::class.java).apply {
                action = OverlayBannerService.ACTION_SHOW
                putExtra(OverlayBannerService.EXTRA_TITLE, "Core Protection Alert")
                putExtra(OverlayBannerService.EXTRA_MESSAGE, "CoreProtect overlay test trigger: if you see this, CoreProtection can launch overlay.")
                putExtra(OverlayBannerService.EXTRA_DELAY_MS, 10_000L) // Display after 10 seconds, convenient to switch to other apps
            }
            // Start service directly within Service, avoid depending on UI context
            startService(intent)
            android.util.Log.d("CoreProtectionService", "OverlayBannerService test trigger scheduled (10s)")
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "Failed to trigger OverlayBannerService test", e)
            overlayTestTriggered = false // Allow retry on failure
        }
    }

    // Permission and related capability snapshot
    private data class PermissionSnapshot(
        val readPhoneState: Boolean,
        val readPhoneNumbers: Boolean?,
        val readContacts: Boolean,
        val readSms: Boolean,
        val receiveSms: Boolean,
        val recordAudio: Boolean,
        val notificationsEnabled: Boolean,
        val postNotificationsGranted: Boolean?,
        val canOverlay: Boolean,
        val accEnabled: Boolean,
        val notiListenerEnabled: Boolean,
    )

    // Calculate whether feature-level capabilities are available, and set corresponding preferences to false when transitioning from available->unavailable
    private fun updatePreferencesOnRevocation(old: PermissionSnapshot, cur: PermissionSnapshot) {
        fun phoneGranted(s: PermissionSnapshot): Boolean {
            val phoneNumOk = s.readPhoneNumbers != false // null treated as no restriction
            return s.readPhoneState && phoneNumOk
        }
        fun notificationsGranted(s: PermissionSnapshot): Boolean {
            val postOk = s.postNotificationsGranted != false // null(below 33) treated as satisfied
            return s.notificationsEnabled && postOk
        }
        fun smsGranted(s: PermissionSnapshot): Boolean = s.readSms && s.receiveSms
        fun microphoneGranted(s: PermissionSnapshot): Boolean = s.recordAudio
        fun contactsGranted(s: PermissionSnapshot): Boolean = s.readContacts
        fun overlayGranted(s: PermissionSnapshot): Boolean = s.canOverlay
        fun accessibilityGranted(s: PermissionSnapshot): Boolean = s.accEnabled

        val before = mapOf(
            "phone_access" to phoneGranted(old),
            "notifications_access" to notificationsGranted(old),
            "contacts_access" to contactsGranted(old),
            "sms_access" to smsGranted(old),
            "microphone_access" to microphoneGranted(old),
            "system_alert" to overlayGranted(old),
            "accessibility_service" to accessibilityGranted(old),
        )
        val after = mapOf(
            "phone_access" to phoneGranted(cur),
            "notifications_access" to notificationsGranted(cur),
            "contacts_access" to contactsGranted(cur),
            "sms_access" to smsGranted(cur),
            "microphone_access" to microphoneGranted(cur),
            "system_alert" to overlayGranted(cur),
            "accessibility_service" to accessibilityGranted(cur),
        )

        // Find feature items that changed from true -> false
        val toDisableKeys = before.keys.filter { key -> before[key] == true && after[key] == false }
        if (toDisableKeys.isEmpty()) return

        serviceScope.launch {
            try {
                dataStore.edit { prefs ->
                    toDisableKeys.forEach { key ->
                        prefs[booleanPreferencesKey(key)] = false
                    }
                }
                android.util.Log.d(
                    "CoreProtectionService",
                    "Prefs set to false due to revocation: ${toDisableKeys.joinToString()}"
                )
            } catch (e: Exception) {
                android.util.Log.w("CoreProtectionService", "Failed to update prefs on revocation", e)
            }
        }
    }

    // ----- Snapshot persistence, cross-process restart comparison for permission revocation -----
    private fun persistSnapshot(s: PermissionSnapshot) {
        try {
            val sp = getSharedPreferences("perm_snapshot", Context.MODE_PRIVATE)
            sp.edit()
                .putInt("ver", 1)
                .putInt("rps", s.readPhoneState.toInt())
                .putInt("rpn", s.readPhoneNumbers.toNullableInt())
                .putInt("rc", s.readContacts.toInt())
                .putInt("rs", s.readSms.toInt())
                .putInt("rcv", s.receiveSms.toInt())
                .putInt("ra", s.recordAudio.toInt())
                .putInt("ne", s.notificationsEnabled.toInt())
                .putInt("png", s.postNotificationsGranted.toNullableInt())
                .putInt("ov", s.canOverlay.toInt())
                .putInt("acc", s.accEnabled.toInt())
                .putInt("nli", s.notiListenerEnabled.toInt())
                .apply()
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "persistSnapshot failed", e)
        }
    }

    private fun loadPersistedSnapshot(): PermissionSnapshot? {
        return try {
            val sp = getSharedPreferences("perm_snapshot", Context.MODE_PRIVATE)
            if (!sp.contains("ver")) {
                null
            } else {
                PermissionSnapshot(
                    readPhoneState = sp.getInt("rps", 0).toBool(),
                    readPhoneNumbers = sp.getInt("rpn", -1).toNullableBool(),
                    readContacts = sp.getInt("rc", 0).toBool(),
                    readSms = sp.getInt("rs", 0).toBool(),
                    receiveSms = sp.getInt("rcv", 0).toBool(),
                    recordAudio = sp.getInt("ra", 0).toBool(),
                    notificationsEnabled = sp.getInt("ne", 0).toBool(),
                    postNotificationsGranted = sp.getInt("png", -1).toNullableBool(),
                    canOverlay = sp.getInt("ov", 0).toBool(),
                    accEnabled = sp.getInt("acc", 0).toBool(),
                    notiListenerEnabled = sp.getInt("nli", 0).toBool(),
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "loadPersistedSnapshot failed", e)
            null
        }
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0
    private fun Int.toBool(): Boolean = this == 1
    private fun Boolean?.toNullableInt(): Int = when (this) {
        true -> 1
        false -> 0
        null -> -1
    }
    private fun Int.toNullableBool(): Boolean? = when (this) {
        1 -> true
        0 -> false
        else -> null
    }


    //MARK: Phone Call

    // ----- Phone state listener, get incoming call number -----
    private fun registerPhoneStateListener() {
        // Check if READ_PHONE_STATE permission is available
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("CoreProtectionService", "READ_PHONE_STATE permission not granted, skip phone state monitoring")
            return
        }

        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ use TelephonyCallback (only for state monitoring)
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        // Only used for listening to state changes, no longer depends on getting number
                        handleCallStateChange(state)
                    }
                }
                telephonyCallback = callback
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
                android.util.Log.d("CoreProtectionService", "TelephonyCallback registered for state monitoring only")
            } else {
                android.util.Log.d("CoreProtectionService", "Phone state monitoring disabled on Android < 12, using notification-only approach")
            }
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "Failed to register phone state listener", e)
        }
    }
    
    private fun handleCallStateChange(state: Int) {
        val stateName = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN($state)"
        }

        android.util.Log.d("CoreProtectionService", "[CALL_STATE] Call state changed: $stateName")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call ringing - state transition to INCOMING (if protection not activated)
                if (callProtectionState == CallProtectionState.INACTIVE) {
                    android.util.Log.d("CoreProtectionService", "[CALL_STATE] Phone ringing but no protection active yet - waiting for notification trigger")
                } else if (callProtectionState == CallProtectionState.INCOMING) {
                    android.util.Log.d("CoreProtectionService", "[CALL_STATE] Phone ringing - protection already active")
                    onCallRinging()
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // In call (answered or outgoing) - state transition to IN_CALL
                if (callProtectionState == CallProtectionState.INCOMING) {
                    callProtectionState = CallProtectionState.IN_CALL
                    onCallAnswered()
                } else {
                    android.util.Log.d("CoreProtectionService", "[CALL_STATE] Call offhook but no incoming protection active")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended or no call - end protection service
                if (callProtectionState != CallProtectionState.INACTIVE) {
                    callProtectionState = CallProtectionState.ENDING
                    onCallEnded()
                }
            }
        }
    }
//MARK: Incoming Call
    // Incoming call ringing state handling
    private fun onCallRinging() {
        // 【Protection Feature - Incoming Call Ringing Phase】Add ringing phase protection logic here
        android.util.Log.d("CoreProtectionService", "[CALL_RINGING] Phone ringing confirmed, number: ${currentIncomingNumber ?: "Unknown"}")
        // Here you can do:
        // 1. Display incoming call risk warning
        // 2. Prepare recording equipment
        // 3. Query more detailed number information
    }
//MARK: In Call
    private fun onCallAnswered() {
        // 【Protection Feature - In Call Phase】Add in-call protection logic here
        android.util.Log.d("CoreProtectionService", "[CALL_ANSWERED] Call answered, transitioning to in-call protection, number: ${currentIncomingNumber ?: "Unknown"}")
        // Here you can do:
        // 1. Start call recording
        // 2. Enable real-time voice analysis
        // 3. Monitor fraud keywords
        // 4. Record call time and quality
    }
//MARK: Call End
    private fun onCallEnded() {
        // 【Protection Feature End Point】Call ended - Add protection logic here for recording stop, post-call analysis, risk reporting, etc.
        android.util.Log.d("CoreProtectionService", "[CALL_ENDED] Call ended")
        if (callProtectionState != CallProtectionState.INACTIVE) {
            android.util.Log.d("CoreProtectionService", "[PROTECTION] Stopping call protection for number: ${currentIncomingNumber ?: "Unknown"}")
            stopCallProtection()
        }
    }
    
    // Extract phone number from dialer notification
    private fun extractPhoneNumberFromNotification(event: NotificationTextEvent): String? {
        val phoneNumberRegex = Regex("[+]?[0-9\\s\\-\\(\\)]{7,15}")
        val title = event.title ?: ""
        val content = event.payloadText
        
        // Try to extract number from title
        var extractedNumber = phoneNumberRegex.find(title)?.value?.replace("[\\s\\-\\(\\)]".toRegex(), "")
        
        // If not found in title, try to extract from content
        if (extractedNumber.isNullOrEmpty()) {
            extractedNumber = phoneNumberRegex.find(content)?.value?.replace("[\\s\\-\\(\\)]".toRegex(), "")
        }
        
        if (!extractedNumber.isNullOrEmpty() && extractedNumber.length >= 7) {
            android.util.Log.d("CoreProtectionService", "[PHONE_EXTRACT] Extracted phone number from notification: $extractedNumber")
            return extractedNumber
        }
        
        return null
    }
    
    // Detect if it's an incoming call notification
    private fun isIncomingCallNotification(event: NotificationTextEvent): Boolean {
        val isDialerApp = event.srcPackage.contains("dialer") || event.srcPackage.contains("phone")
        val isIncomingCall = event.payloadText.contains("Incoming", ignoreCase = true) &&
                            (event.payloadText.contains("call", ignoreCase = true) ||
                             event.payloadText.contains("Wi‑Fi call", ignoreCase = true) ||
                             event.payloadText.contains("video call", ignoreCase = true))
        val hasPhoneNumber = event.title?.matches(Regex(".*[0-9]{3,}.*")) == true
        
        android.util.Log.d("CoreProtectionService", "[CALL_DETECT] isDialer=$isDialerApp, isIncoming=$isIncomingCall, hasNumber=$hasPhoneNumber, pkg=${event.srcPackage}, text=${event.payloadText.take(100)}")
        
        return isDialerApp && isIncomingCall && hasPhoneNumber
    }
    
    // Check if this incoming call notification should be processed (filter duplicates)
    private fun shouldProcessCallNotification(event: NotificationTextEvent): Boolean {
        // If already processing the same notification ID, skip
        if (event.notificationId == lastProcessedCallNotificationId) {
            return false
        }
        
        // If phone protection is already active, also skip (prevent duplicate activation)
        if (callProtectionState != CallProtectionState.INACTIVE) {
            return false
        }
        
        return true
    }
    
    // Handle incoming call events from notifications (main trigger point)
    private fun handleIncomingCallFromNotification(event: NotificationTextEvent) {
        // Do NOT mark notification ID as processed yet. Dialer often reuses ID=1 for each call.
        // We first extract number and run allowlist logic; only if we actually activate protection
        // do we record the notificationId. This ensures that newly added allowlist numbers
        // (added while app is running) still get evaluated even with reused notification IDs.
        // Extract phone number
        val phoneNumber = extractPhoneNumberFromNotification(event)
        if (phoneNumber == null) {
            android.util.Log.w("CoreProtectionService", "[NOTIFICATION_TRIGGER] Failed to extract phone number from incoming call notification")
            return
        }

        // Check call protection feature toggle and allowlist before starting protection (async, non-blocking)
        serviceScope.launch {
            // 1. Check if call protection is enabled in user preferences
            val protectionEnabled = try {
                isCallProtectionEnabled()
            } catch (e: Exception) {
                android.util.Log.w("CoreProtectionService", "[PROTECTION_TOGGLE] Failed to check call protection preference (fallback to disabled)", e)
                false
            }

            if (!protectionEnabled) {
                android.util.Log.d("CoreProtectionService", "[PROTECTION_TOGGLE] Call protection is disabled in user preferences; skip protection for $phoneNumber")
                lastProcessedCallNotificationId = -1
                return@launch
            }

            // 2. Check allowlist
            val allowlisted = try {
                checkPhoneAllowlisted(phoneNumber)
            } catch (e: Exception) {
                android.util.Log.w("CoreProtectionService", "[ALLOWLIST] Allowlist check failed (fallback to protection)", e)
                false
            }

            if (allowlisted) {
                android.util.Log.d("CoreProtectionService", "[ALLOWLIST] Number $phoneNumber is in allowlist; skip call protection activation")
                // IMPORTANT: Because Google Dialer reuses a fixed notificationId (often 1) for each incoming call,
                // keeping lastProcessedCallNotificationId set would cause the next DIFFERENT call to be treated as a duplicate
                // and skipped before we can re-run allowlist logic. Since we did NOT enter protection state here,
                // we must clear the processed marker so the next call notification (even with same ID) is re-evaluated.
                lastProcessedCallNotificationId = -1
                return@launch
            }

            // 3. Both protection enabled and number not in allowlist - enrich with external risk data
            currentIncomingRisk = null
            val riskResult = try {
                withTimeoutOrNull(CYBERTRACE_TIMEOUT_MS) {
                    cybertraceRiskClient.lookup(phoneNumber)
                }
            } catch (e: Exception) {
                android.util.Log.w("CoreProtectionService", "[CYBERTRACE] Lookup failed for $phoneNumber", e)
                null
            }

            riskResult?.let {
                currentIncomingRisk = it
                val statement = it.riskStatement ?: it.riskLevel.name.lowercase(Locale.ROOT)
                val countSummary = buildString {
                    it.reportedCount?.takeIf { c -> c > 0 }?.let { c -> append("reported $c times") }
                    val searched = it.searchedCount?.takeIf { c -> c > 0 }
                    if (searched != null) {
                        if (isNotEmpty()) append(", ")
                        append("searched $searched times")
                    }
                }
                android.util.Log.d(
                    "CoreProtectionService",
                    "[CYBERTRACE] Risk score=${it.riskPercent ?: -1}% level=${it.riskLevel} statement=$statement ${if (countSummary.isNotEmpty()) "($countSummary)" else ""}"
                )

                if (it.riskLevel == CybertraceRiskClient.RiskLevel.HIGH ||
                    it.riskLevel == CybertraceRiskClient.RiskLevel.MEDIUM ||
                    (it.reportedCount ?: 0) > 0
                ) {
                    maybeShowCybertraceRiskBanner(phoneNumber, it)
                }
            }

            // 4. Start protection after enrichment
            currentIncomingNumber = phoneNumber
            android.util.Log.d(
                "CoreProtectionService",
                "[NOTIFICATION_TRIGGER] Starting protection for incoming call: $phoneNumber (protection enabled, not in allowlist)"
            )
            // Now mark this notification ID as processed because we are entering protection flow.
            lastProcessedCallNotificationId = event.notificationId
            startCallProtection(phoneNumber)
        }
    }

    /**
     * Check if call protection feature is enabled in user preferences
     */
    private suspend fun isCallProtectionEnabled(): Boolean {
        return try {
            val preferences = dataStore.data.first()
            preferences[booleanPreferencesKey("call_protection_enabled")] ?: false
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "Failed to read call protection preference", e)
            false
        }
    }

    /**
     * Check if meeting protection feature is enabled in user preferences
     */
    private suspend fun isMeetingProtectionEnabled(): Boolean {
        return try {
            val preferences = dataStore.data.first()
            preferences[booleanPreferencesKey("meetingProtection")] ?: false
        } catch (e: Exception) {
            android.util.Log.w("CoreProtectionService", "Failed to read meeting protection preference", e)
            false
        }
    }

    /**
     * Allowlist matching (lenient variants):
     * - Raw extracted form
     * - Digits only (remove + / spaces / symbols)
     * - Digits only with leading + (handles user input stored with + but extracted without)
     * If any variant matches, number is considered allow‑listed.
     */
    private suspend fun checkPhoneAllowlisted(rawNumber: String): Boolean {
        val digitsOnly = rawNumber.filter { it.isDigit() }
        val variants = linkedSetOf(
            rawNumber,
            digitsOnly,
            if (digitsOnly.isNotEmpty()) "+$digitsOnly" else null
        ).filterNot { it.isNullOrBlank() }.map { it!! }

        val repo = AntiCenterRepository.getInstance(applicationContext)
        android.util.Log.d("CoreProtectionService", "[ALLOWLIST_DEBUG] variants=${variants.joinToString()}")
        for (candidate in variants) {
            try {
                android.util.Log.d("CoreProtectionService", "[ALLOWLIST_DEBUG] querying candidate=$candidate feature=${SelectFeatures.callProtection}")
                if (repo.isValueInAllowlist(candidate, SelectFeatures.callProtection)) {
                    android.util.Log.d("CoreProtectionService", "[ALLOWLIST_MATCH] candidate=$candidate raw=$rawNumber")
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w("CoreProtectionService", "[ALLOWLIST_QUERY_FAIL] candidate=$candidate", e)
            }
        }
        return false
    }
    
    /**
     * Start BCR recording monitoring (monitors BCR's recording directory)
     */
    private fun startCallRecordingMonitor() {
        try {
            android.util.Log.d("CoreProtectionService", "[BCR_MONITOR] Starting BCR recording monitor")
            
            if (bcrMonitor == null) {
                bcrMonitor = BCRMonitorCollector(this)
            }
            
            // 在协程中启动监控
            serviceScope.launch {
                try {
                    bcrMonitor?.startCollection()
                    android.util.Log.d("CoreProtectionService", "[BCR_MONITOR] BCR monitor started successfully")
                } catch (e: Exception) {
                    android.util.Log.e("CoreProtectionService", "[BCR_MONITOR] Failed to start BCR monitor", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "[BCR_MONITOR] Failed to initialize BCR monitor", e)
        }
    }

    /**
     * Stop BCR recording monitoring
     */
    private fun stopCallRecordingMonitor() {
        try {
            android.util.Log.d("CoreProtectionService", "[BCR_MONITOR] Stopping BCR recording monitor")
            
            serviceScope.launch {
                try {
                    bcrMonitor?.stopCollection()
                    bcrMonitor = null
                    android.util.Log.d("CoreProtectionService", "[BCR_MONITOR] BCR monitor stopped successfully")
                } catch (e: Exception) {
                    android.util.Log.e("CoreProtectionService", "[BCR_MONITOR] Error stopping BCR monitor", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", "[BCR_MONITOR] Failed to stop BCR monitor", e)
        }
    }

    // Start phone protection service
    //MARK: - Protection logic entry point
    private fun startCallProtection(phoneNumber: String) {
        if (callProtectionState != CallProtectionState.INACTIVE) {
            android.util.Log.d("CoreProtectionService", "[PROTECTION] Call protection already active, state: $callProtectionState")
            return
        }
        
        android.util.Log.d("CoreProtectionService", "[PROTECTION] Starting call protection for number: $phoneNumber")
        callProtectionState = CallProtectionState.INCOMING
        isCallProtectionActive = true
        
        // 【Protection Feature Implementation Area - Incoming Call Phase】Add specific protection logic here:
        // 1. Check if number is in blacklist
        // 2. Query number risk level
        // 3. Start recording monitoring service (让录音服务待命)
        startCallRecordingMonitor()
        // 4. Display risk warning (if needed)
        // 5. Start real-time monitoring service
    }
    
    // Stop phone protection service
    private fun stopCallProtection() {
        android.util.Log.d("CoreProtectionService", "[PROTECTION] Stopping call protection, previous state: $callProtectionState")
        callProtectionState = CallProtectionState.INACTIVE
        isCallProtectionActive = false
        
        // 【Protection Feature Cleanup Area】Add cleanup logic here:
        // 1. Stop recording service
        stopCallRecordingMonitor()
        // 2. Close monitoring service
        // 3. Generate call report
        // 4. Clean temporary files
        // 5. Update blacklist (if needed)
        
        // Clear cached incoming number and notification ID
        currentIncomingNumber = null
        lastProcessedCallNotificationId = -1
        currentIncomingRisk = null
    }

    //MARK: Zoom Meeting


    // ----- Zoom meeting protection feature -----
    
    // Detect if it's a Zoom meeting notification (insert in notification queue for detection)
    private fun isZoomMeetingNotification(event: NotificationTextEvent): Boolean {
        val isZoomApp = event.srcPackage.contains("zoom", ignoreCase = true)
        val isMeetingInProgress = event.payloadText.contains("Meeting in progress", ignoreCase = true) ||
                                 event.payloadText.contains("会议进行中", ignoreCase = true)
        val isZoomTitle = event.title?.contains("Zoom", ignoreCase = true) == true
        
        return isZoomApp && isMeetingInProgress && isZoomTitle
    }
    
    // Check if this Zoom meeting notification should be processed (filter duplicates)
    private fun shouldProcessZoomNotification(event: NotificationTextEvent): Boolean {
        android.util.Log.d("CoreProtectionService", 
            "[ZOOM_FILTER_DEBUG] Checking notification: id=${event.notificationId}, " +
            "lastProcessed=$lastProcessedZoomNotificationId, " +
            "protectionActive=$isZoomProtectionActive")
        
        // If already processing the same notification ID, skip
        if (event.notificationId == lastProcessedZoomNotificationId) {
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_FILTER_DEBUG] ❌ Blocked: Same notification ID already processed")
            return false
        }
        
        // If Zoom protection is already active, also skip (prevent duplicate activation)
        if (isZoomProtectionActive) {
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_FILTER_DEBUG] ❌ Blocked: Protection already active")
            return false
        }
        
        android.util.Log.d("CoreProtectionService", 
            "[ZOOM_FILTER_DEBUG] ✅ Passed: Will process this notification")
        return true
    }
    
    // Handle Zoom meeting events from notifications (main trigger point)
    private fun handleZoomMeetingFromNotification(event: NotificationTextEvent) {
        // Record processed notification ID
        lastProcessedZoomNotificationId = event.notificationId
        
        // Extract meeting information (can get from notificationId or other fields)
        val meetingId = "meeting_${event.notificationId}_${System.currentTimeMillis()}"
        
        currentZoomMeetingId = meetingId
        // 【Protection Feature Main Trigger Point】Zoom meeting detection - Add protection logic here for screen recording, real-time monitoring, anti-fraud detection, etc.
        android.util.Log.d("CoreProtectionService", "[ZOOM_MEETING_STARTED] 🚀 Zoom meeting started, detected from notification: $meetingId")
        android.util.Log.d("CoreProtectionService", "[ZOOM_TRIGGER] Zoom meeting detected from notification: $meetingId")
        startZoomProtection(meetingId)
    }
    
    // Start Zoom meeting protection service
    //TODO: Add specific meeting protection logic later
    //MARK: - Zoom Protection logic entry point
    private fun startZoomProtection(meetingId: String) {
        if (isZoomProtectionActive) {
            android.util.Log.d("CoreProtectionService", "[ZOOM_PROTECTION] Zoom protection already active, updating meeting: $meetingId")
            return
        }
        
        android.util.Log.d("CoreProtectionService", "[ZOOM_PROTECTION] 🛡️ Starting Zoom protection for meeting: $meetingId")
        
        // Check meeting protection feature toggle before starting protection (async, non-blocking)
        serviceScope.launch {
            try {
                // 1. Check if meeting protection is enabled in user preferences
                val protectionEnabled = isMeetingProtectionEnabled()
                
                if (!protectionEnabled) {
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] Meeting protection is disabled in user preferences; skip protection for $meetingId")
                    return@launch
                }
                
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] ✅ Meeting protection enabled - proceeding with protection activation")
                isZoomProtectionActive = true
                
                // Start ZoomCollector service
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] 🚀 Starting ZoomCapService for meeting: $meetingId")
                
                val zoomCollector = ZoomCollector.getInstance()
                if (zoomCollector != null) {
                    // 直接在Service中显示权限对话框（浮窗方式）
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] Requesting MediaProjection permission via floating dialog for meeting: $meetingId")
                    
                    requestMediaProjectionInService(meetingId)
                } else {
                    android.util.Log.w("CoreProtectionService", 
                        "[ZOOM_PROTECTION] ZoomCollector instance not found - service not initialized")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", 
                    "[ZOOM_PROTECTION] Failed to start Zoom protection", e)
            }
        }
    }
    
    /**
     * 在Service中通过通知方式请求MediaProjection权限
     * 解决Android 10+限制Background Service启动Activity的问题
     */
    private fun requestMediaProjectionInService(meetingId: String) {
        try {
            // 保存待处理的会议ID
            pendingZoomMeetingId = meetingId
            isWaitingForMediaProjection = true
            
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_PROTECTION] 🎯 Requesting MediaProjection permission via notification for meeting: $meetingId")
            
            // 显示权限请求通知
            showMediaProjectionPermissionNotification(meetingId)
            
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", 
                "[ZOOM_PROTECTION] Failed to show MediaProjection permission notification", e)
            handleMediaProjectionPermissionFailed(meetingId)
        }
    }
    
    /**
     * 显示MediaProjection权限请求通知
     */
    private fun showMediaProjectionPermissionNotification(meetingId: String) {
        try {
            // 创建指向MediaProjectionPermissionActivity的Intent
            val permissionIntent = Intent(this, com.example.anticenter.activities.MediaProjectionPermissionActivity::class.java).apply {
                putExtra(com.example.anticenter.activities.MediaProjectionPermissionActivity.EXTRA_MEETING_ID, meetingId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            // 使用PendingIntent包装（用于点击通知）
            val pendingIntent = PendingIntent.getActivity(
                this,
                MEDIA_PROJECTION_REQUEST_CODE,
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建Full-Screen Intent（用于强制弹出）
            val fullScreenIntent = PendingIntent.getActivity(
                this,
                MEDIA_PROJECTION_REQUEST_CODE + 1,  // 不同的request code
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 创建通知渠道（Android 8.0+需要）
            createMediaProjectionNotificationChannel()
            
            // 构建通知 - 配置为强制弹出的 Heads-up 通知
            val notification = NotificationCompat.Builder(this, MEDIA_PROJECTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_warning_24) // 使用现有的警告图标
                .setContentTitle("Screen Recording Permission Required")
                .setContentText("Zoom meeting detected. Tap to authorize screen recording protection")
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(fullScreenIntent, true)  // 🔥 强制全屏弹出
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)  // 🔥 改为最高优先级
                .setCategory(NotificationCompat.CATEGORY_ALARM)  // 🔥 改为闹钟类别（更激进）
                .setDefaults(NotificationCompat.DEFAULT_ALL)  // 🔥 启用声音、振动、灯光
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // 锁屏也显示
                .setOngoing(false)  // 允许滑动清除
                .build()
            
            // 显示通知
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(MEDIA_PROJECTION_NOTIFICATION_ID, notification)
            
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_PROTECTION] 📱 MediaProjection permission notification displayed")
            
            // 权限状态将通过广播接收器处理，无需启动轮询监控
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_PROTECTION] 🎯 Waiting for user to click notification and grant permission...")
            
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", 
                "[ZOOM_PROTECTION] Failed to show permission notification", e)
            handleMediaProjectionPermissionFailed(meetingId)
        }
    }
    
    /**
     * 创建MediaProjection通知渠道
     */
    private fun createMediaProjectionNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MEDIA_PROJECTION_CHANNEL_ID,
                "Screen Recording Permission",
                NotificationManager.IMPORTANCE_HIGH  // 最高级别,确保弹出
            ).apply {
                description = "Critical notifications for requesting screen recording permission during Zoom meetings"
                enableVibration(true)
                enableLights(true)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC  // 锁屏显示
                setBypassDnd(true)  // 🔥 允许绕过免打扰模式
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            android.util.Log.d("CoreProtectionService", 
                "[NOTIFICATION_CHANNEL] MediaProjection channel created with IMPORTANCE_HIGH and bypass DND")
        }
    }
    
    /**
     * 监听MediaProjection权限授权状态
     */
    private fun startMediaProjectionPermissionMonitoring() {
        // 取消之前的监听任务
        mediaProjectionCheckJob?.cancel()
        
        mediaProjectionCheckJob = serviceScope.launch {
            var attemptCount = 0
            val maxAttempts = 30 // 最多等待30秒
            
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_PROTECTION] 🔍 Starting MediaProjection permission monitoring...")
            
            while (isWaitingForMediaProjection && attemptCount < maxAttempts) {
                try {
                    // 检查是否有可用的MediaProjection实例（表示权限已授权）
                    if (checkMediaProjectionAvailable()) {
                        android.util.Log.d("CoreProtectionService", 
                            "[ZOOM_PROTECTION] ✅ MediaProjection permission granted! Starting ZoomCapService...")
                        
                        handleMediaProjectionPermissionGranted()
                        break
                    }
                    
                    delay(1000) // 每秒检查一次
                    attemptCount++
                    
                } catch (e: Exception) {
                    android.util.Log.e("CoreProtectionService", 
                        "[ZOOM_PROTECTION] Error during permission monitoring", e)
                    break
                }
            }
            
            // 超时处理
            if (isWaitingForMediaProjection && attemptCount >= maxAttempts) {
                android.util.Log.w("CoreProtectionService", 
                    "[ZOOM_PROTECTION] ⏰ MediaProjection permission request timeout")
                handleMediaProjectionPermissionTimeout()
            }
        }
    }
    
    /**
     * 检查MediaProjection权限是否可用
     * 注意：由于Service无法直接获取ActivityResult，这里主要用于检测用户是否已完成授权流程
     */
    private fun checkMediaProjectionAvailable(): Boolean {
        return try {
            // 临时实现：检查ZoomCapService是否可以启动（间接表示权限可能已获得）
            // 在真实场景中，我们需要通过其他方式（如SharedPreferences或文件）来跟踪权限状态
            android.util.Log.d("CoreProtectionService", 
                "[ZOOM_PROTECTION] 🔍 Checking MediaProjection permission status...")
            
            // 这里暂时返回false，等待更好的检测机制
            // 用户点击通知并授权后，应该通过其他方式通知Service
            false
        } catch (e: Exception) {
            android.util.Log.e("CoreProtectionService", 
                "[ZOOM_PROTECTION] Error checking MediaProjection availability", e)
            false
        }
    }
    
    /**
     * 处理MediaProjection权限授权成功
     */
    private fun handleMediaProjectionPermissionGranted() {
        val meetingId = pendingZoomMeetingId ?: return
        
        serviceScope.launch {
            try {
                isWaitingForMediaProjection = false
                
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] 🎉 MediaProjection permission granted, starting ZoomCapService for meeting: $meetingId")
                
                val zoomCollector = ZoomCollector.getInstance()
                if (zoomCollector != null) {
                    // 注意：这里需要实际的MediaProjection consent，但在浮窗模式下我们无法直接获取
                    // 可能需要修改ZoomCollector来支持无consent启动，或使用其他方法
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] 🚀 Attempting to start ZoomCapService for meeting: $meetingId")
                    
                    // 这里暂时使用一个占位Intent，实际需要从权限对话框获取真实consent
                    val placeholderConsent = Intent()
                    val started = zoomCollector.startWithConsent(placeholderConsent)
                    
                    if (started) {
                        android.util.Log.d("CoreProtectionService", 
                            "[ZOOM_PROTECTION] ✅ ZoomCapService started successfully for meeting: $meetingId")
                    } else {
                        android.util.Log.e("CoreProtectionService", 
                            "[ZOOM_PROTECTION] ❌ Failed to start ZoomCapService for meeting: $meetingId")
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", 
                    "[ZOOM_PROTECTION] Error handling MediaProjection permission granted", e)
            } finally {
                pendingZoomMeetingId = null
            }
        }
    }
    
    /**
     * 处理MediaProjection权限请求失败
     */
    private fun handleMediaProjectionPermissionFailed(meetingId: String) {
        serviceScope.launch {
            isWaitingForMediaProjection = false
            isZoomProtectionActive = false
            
            android.util.Log.w("CoreProtectionService", 
                "[ZOOM_PROTECTION] ❌ MediaProjection permission failed for meeting: $meetingId")
            
            pendingZoomMeetingId = null
        }
    }
    
    /**
     * 处理MediaProjection权限请求超时
     */
    private fun handleMediaProjectionPermissionTimeout() {
        val meetingId = pendingZoomMeetingId ?: return
        
        android.util.Log.w("CoreProtectionService", 
            "[ZOOM_PROTECTION] ⏰ MediaProjection permission request timeout for meeting: $meetingId")
        
        handleMediaProjectionPermissionFailed(meetingId)
    }
    
    // Stop Zoom meeting protection service
    // Triggered by notification removal, app switching, or timeout
    private fun stopZoomProtection() {
        val endedMeetingId = currentZoomMeetingId ?: "unknown"
        android.util.Log.d("CoreProtectionService", "[ZOOM_MEETING_ENDED] 🔚 Zoom meeting ended, stopping protection for meeting: $endedMeetingId")
        android.util.Log.d("CoreProtectionService", "[ZOOM_PROTECTION] Stopping Zoom protection")
        isZoomProtectionActive = false
        
        // 清理MediaProjection权限请求相关状态
        isWaitingForMediaProjection = false
        pendingZoomMeetingId = null
        mediaProjectionCheckJob?.cancel()
        mediaProjectionCheckJob = null
        
        serviceScope.launch {
            try {
                // Stop ZoomCollector service
                android.util.Log.d("CoreProtectionService", 
                    "[ZOOM_PROTECTION] 🛑 Stopping ZoomCapService for meeting: $endedMeetingId")
                
                val zoomCollector = ZoomCollector.getInstance()
                if (zoomCollector != null) {
                    zoomCollector.stop()
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] ZoomCollector stopped successfully")
                } else {
                    android.util.Log.w("CoreProtectionService", 
                        "[ZOOM_PROTECTION] ZoomCollector instance not found")
                }
                
                // Generate meeting security report (if needed)
                currentZoomMeetingId?.let { meetingId ->
                    android.util.Log.d("CoreProtectionService", 
                        "[ZOOM_PROTECTION] Meeting ended: $meetingId")
                    // TODO: Generate meeting summary report
                }
                
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", 
                    "[ZOOM_PROTECTION] Error during Zoom protection cleanup", e)
            }
        }
        
        // Clear cached meeting information and notification ID
        currentZoomMeetingId = null
        lastProcessedZoomNotificationId = -1
        
        android.util.Log.d("CoreProtectionService", 
            "[ZOOM_PROTECTION] ✅ All states reset: " +
            "protectionActive=$isZoomProtectionActive, " +
            "lastProcessedId=$lastProcessedZoomNotificationId, " +
            "currentMeetingId=$currentZoomMeetingId")
    }

    //MARK: Testing Methods (for development and verification)
    /**
     * Test method to verify PhishingDataHub integration
     * This method can be called to simulate phishing data events
     */
    private fun testPhishingDataIntegration() {
        serviceScope.launch {
            try {
                // Test with a simulated phishing email
                val testEmailData = PhishingData(
                    dataType = "Email",
                    content = "Test phishing email content",
                    metadata = mapOf(
                        "subject" to "Urgent: Verify Your Account",
                        "sender" to "fake@phishing.com",
                        "llmDecision" to "phishing",
                        "llmExplanation" to "This email contains suspicious links and urgency tactics",
                        "messageId" to "test_msg_123",
                        "timestamp" to System.currentTimeMillis().toString()
                    )
                )
                
                android.util.Log.d("CoreProtectionService", "[TEST] Sending test phishing data to hub")
                PhishingDataHub.addData(testEmailData)
                
            } catch (e: Exception) {
                android.util.Log.e("CoreProtectionService", "[TEST] Failed to test phishing data integration", e)
            }
        }
    }
}
