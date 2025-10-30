package com.example.anticenter.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.anticenter.ui.components.PermissionDeniedDialog

/**
 * Check and request phone permissions
 * 
 * This function checks READ_PHONE_STATE and READ_PHONE_NUMBERS permissions,
 * automatically shows system permission request dialog if permissions are not granted.
 * If user denies permissions, displays a guidance dialog directing user to settings page for manual enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensurePhonePermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkPhonePermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensurePhonePermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensurePhonePermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        Log.d("PermissionUtils", "ensurePhonePermission: permission request result = $permissions")
        Log.d("PermissionUtils", "ensurePhonePermission: all permissions granted = $allGranted")
        
        permissionState = permissionState.copy(
            hasPermission = allGranted,
            isRequesting = false,
            hasRequested = true,
            shouldShowDialog = !allGranted
        )
        
        if (!allGranted) {
            Log.d("PermissionUtils", "ensurePhonePermission: permission denied, setting shouldShowDialog = true")
        }
    }
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensurePhonePermission: initiating phone permission request")
            permissionState = permissionState.copy(isRequesting = true)
            val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            launcher.launch(permissions.toTypedArray())
        } else {
            Log.d("PermissionUtils", "ensurePhonePermission: already have phone permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Phone Permission Required",
        message = "The app needs phone access to detect suspicious calls. Please enable the permission in Settings.",
        settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        },
        onDismiss = { 
            Log.d("PermissionUtils", "ensurePhonePermission: dialog dismissed")
            val currentPermission = checkPhonePermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensurePhonePermission: final return status = $permissionState")
    return permissionState
}

/**
 * Permission request state
 */
data class PermissionState(
    val hasPermission: Boolean = false,
    val isRequesting: Boolean = false,
    val hasRequested: Boolean = false,
    val shouldShowDialog: Boolean = false
)

/**
 * Check and request SMS permissions
 * 
 * This function checks READ_SMS and RECEIVE_SMS permissions,
 * used to read existing SMS content and receive new SMS notifications for detecting fraudulent messages.
 * Automatically shows system permission request dialog if permissions are not granted.
 * If user denies permissions, displays a guidance dialog directing user to settings page for manual enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureSmsPermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkSmsPermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureSmsPermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureSmsPermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        Log.d("PermissionUtils", "ensureSmsPermission: permission request result = $permissions")
        Log.d("PermissionUtils", "ensureSmsPermission: all permissions granted = $allGranted")
        
        permissionState = permissionState.copy(
            hasPermission = allGranted,
            isRequesting = false,
            hasRequested = true,
            shouldShowDialog = !allGranted // Only show dialog when denied
        )
        
        if (!allGranted) {
            Log.d("PermissionUtils", "ensureSmsPermission: permission denied, setting shouldShowDialog = true")
        }
    }
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensureSmsPermission: initiating SMS permission request")
            permissionState = permissionState.copy(isRequesting = true)
            launcher.launch(arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            ))
        } else {
            Log.d("PermissionUtils", "ensureSmsPermission: already have SMS permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "SMS Permission Required",
        message = "The app needs SMS access to detect fraudulent messages. Please enable the permission in Settings.",
        settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        },
        onDismiss = { 
            Log.d("PermissionUtils", "ensureSmsPermission: dialog dismissed")
            val currentPermission = checkSmsPermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureSmsPermission: final return status = $permissionState")
    return permissionState
}

/**
 * Check and request microphone permission
 * 
 * This function checks RECORD_AUDIO permission,
 * used to record audio during calls to analyze if there are fraud risk voice contents.
 * Automatically shows system permission request dialog if permission is not granted.
 * If user denies permission, displays a guidance dialog directing user to settings page for manual enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureMicrophonePermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkMicrophonePermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureMicrophonePermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureMicrophonePermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("PermissionUtils", "ensureMicrophonePermission: permission request result = $granted")
        
        permissionState = permissionState.copy(
            hasPermission = granted,
            isRequesting = false,
            hasRequested = true,
            shouldShowDialog = !granted
        )
        
        if (!granted) {
            Log.d("PermissionUtils", "ensureMicrophonePermission: permission denied, setting shouldShowDialog = true")
        }
    }
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensureMicrophonePermission: initiating microphone permission request")
            permissionState = permissionState.copy(isRequesting = true)
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d("PermissionUtils", "ensureMicrophonePermission: already have microphone permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Microphone Permission Required",
        message = "The app needs microphone access to analyze risky voice content during calls. Please enable the permission in Settings.",
        settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        },
        onDismiss = { 
            Log.d("PermissionUtils", "ensureMicrophonePermission: dialog dismissed")
            val currentPermission = checkMicrophonePermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureMicrophonePermission: final return status = $permissionState")
    return permissionState
}

/**
 * Check and request contacts permission
 * 
 * This function checks READ_CONTACTS permission,
 * used to read user contact information to build a trusted contacts whitelist,
 * for more accurate identification of suspicious calls and messages.
 * Automatically shows system permission request dialog if permission is not granted.
 * If user denies permission, displays a guidance dialog directing user to settings page for manual enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureContactsPermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkContactsPermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureContactsPermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureContactsPermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("PermissionUtils", "ensureContactsPermission: permission request result = $granted")
        
        permissionState = permissionState.copy(
            hasPermission = granted,
            isRequesting = false,
            hasRequested = true,
            shouldShowDialog = !granted
        )
        
        if (!granted) {
            Log.d("PermissionUtils", "ensureContactsPermission: permission denied, setting shouldShowDialog = true")
        }
    }
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensureContactsPermission: initiating contacts permission request")
            permissionState = permissionState.copy(isRequesting = true)
            launcher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            Log.d("PermissionUtils", "ensureContactsPermission: already have contacts permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Contacts Permission Required",
        message = "The app needs contacts access to build a trusted contacts whitelist. Please enable the permission in Settings.",
        settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        },
        onDismiss = { 
            Log.d("PermissionUtils", "ensureContactsPermission: dialog dismissed")
            val currentPermission = checkContactsPermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureContactsPermission: final return status = $permissionState")
    return permissionState
}

/**
 * Check and request notification permission (Android 13+)
 * 
 * This function checks POST_NOTIFICATIONS permission (only on Android 13 and above).
 * Used to send anti-fraud alert notifications to promptly inform users of suspicious activities.
 * Android versions below 13 have notification permission by default.
 * Automatically shows system permission request dialog if permission is not granted.
 * If user denies permission, displays a guidance dialog directing user to settings page for manual enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureNotificationPermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkNotificationPermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureNotificationPermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureNotificationPermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("PermissionUtils", "ensureNotificationPermission: permission request result = $granted")
        
        permissionState = permissionState.copy(
            hasPermission = granted,
            isRequesting = false,
            hasRequested = true,
            shouldShowDialog = !granted // Only show dialog when denied
        )
        
        if (!granted) {
            Log.d("PermissionUtils", "ensureNotificationPermission: permission denied, setting shouldShowDialog = true")
        }
    }
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d("PermissionUtils", "ensureNotificationPermission: initiating notification permission request")
            permissionState = permissionState.copy(isRequesting = true)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Log.d("PermissionUtils", "ensureNotificationPermission: already have notification permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Notification Permission Required",
        message = "The app needs notification access to alert you about suspicious activities. Please enable the permission in Settings.",
        settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        },
        onDismiss = { 
            Log.d("PermissionUtils", "ensureNotificationPermission: dialog dismissed")
            val currentPermission = checkNotificationPermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureNotificationPermission: final return status = $permissionState")
    return permissionState
}

/**
 * Check and request overlay permission
 * 
 * This function checks SYSTEM_ALERT_WINDOW permission,
 * used to display floating risk warning windows on top of other app interfaces,
 * so users can see security alerts promptly even when using other apps.
 * Since this is a special permission that cannot be obtained directly through system dialog,
 * it displays a guidance dialog directing user to system settings page for manual enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureOverlayPermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkOverlayPermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureOverlayPermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureOverlayPermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensureOverlayPermission: need overlay permission, setting shouldShowDialog = true")
            permissionState = permissionState.copy(
                hasRequested = true,
                shouldShowDialog = true
            )
        } else {
            Log.d("PermissionUtils", "ensureOverlayPermission: already have overlay permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Overlay Permission Required",
        message = "The app needs overlay permission to display risk warnings on top of other apps. Please enable the permission in Settings.",
        settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
        },
        onDismiss = { 
            Log.d("PermissionUtils", "ensureOverlayPermission: dialog dismissed")
            val currentPermission = checkOverlayPermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureOverlayPermission: final return status = $permissionState")
    return permissionState
}

/**
 * Check and request accessibility service permission
 * 
 * This function checks if AntiCenter accessibility service is enabled in the system.
 * Accessibility service is used to monitor user interface operations and app behaviors,
 * to identify suspicious automated operations or phishing app interfaces.
 * Since this is a special permission that cannot be obtained directly through system dialog,
 * it displays a guidance dialog directing user to accessibility settings page for manual service enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureAccessibilityPermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkAccessibilityPermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureAccessibilityPermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureAccessibilityPermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensureAccessibilityPermission: need accessibility service permission, setting shouldShowDialog = true")
            permissionState = permissionState.copy(
                hasRequested = true,
                shouldShowDialog = true
            )
        } else {
            Log.d("PermissionUtils", "ensureAccessibilityPermission: already have accessibility service permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Accessibility Service Required",
        message = "The app needs accessibility service to monitor suspicious UI operations and app behaviors. Please enable AntiCenter service in Accessibility settings.",
        settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        onDismiss = { 
            Log.d("PermissionUtils", "ensureAccessibilityPermission: dialog dismissed")
            val currentPermission = checkAccessibilityPermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureAccessibilityPermission: final return status = $permissionState")
    return permissionState
}

/**
 * Check and request notification listener service permission
 * 
 * This function checks if AntiCenter notification listener service is enabled in the system.
 * Notification listener service is used to monitor notification content from all apps in the system in real time,
 * to identify suspicious push messages, phishing links, or fraudulent content.
 * Since this is a special permission that cannot be obtained directly through system dialog,
 * it displays a guidance dialog directing user to notification access settings page for manual service enabling.
 * 
 * @return PermissionState Permission state object
 */
@Composable
fun ensureNotificationListenerPermission(): PermissionState {
    val context = LocalContext.current
    var permissionState by remember(context) { 
        mutableStateOf(PermissionState(hasPermission = checkNotificationListenerPermission(context)))
    }
    
    // Record initial permission status
    Log.d("PermissionUtils", "ensureNotificationListenerPermission: initial permission status = ${permissionState.hasPermission}")
    Log.d("PermissionUtils", "ensureNotificationListenerPermission: initial shouldShowDialog status = ${permissionState.shouldShowDialog}")
    
    LaunchedEffect(permissionState.hasRequested) {
        if (!permissionState.hasPermission && !permissionState.hasRequested) {
            Log.d("PermissionUtils", "ensureNotificationListenerPermission: need notification listener service permission, setting shouldShowDialog = true")
            permissionState = permissionState.copy(
                hasRequested = true,
                shouldShowDialog = true
            )
        } else {
            Log.d("PermissionUtils", "ensureNotificationListenerPermission: already have notification listener service permission or already requested, no need to request")
        }
    }
    
    PermissionDeniedDialog(
        showDialog = permissionState.shouldShowDialog,
        title = "Notification Access Required",
        message = "The app needs notification access to detect suspicious push messages. Please enable AntiCenter service in Notification access settings.",
        settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        onDismiss = { 
            Log.d("PermissionUtils", "ensureNotificationListenerPermission: dialog dismissed")
            val currentPermission = checkNotificationListenerPermission(context)
            permissionState = permissionState.copy(
                hasPermission = currentPermission,
                shouldShowDialog = false
            )
        }
    )
    
    // Record final return value
    Log.d("PermissionUtils", "ensureNotificationListenerPermission: final return status = $permissionState")
    return permissionState
}

// Helper check functions

/**
 * Check phone permission status
 * 
 * Check if READ_PHONE_STATE and READ_PHONE_NUMBERS permissions have been granted.
 * Android versions below 13 do not need READ_PHONE_NUMBERS permission.
 * 
 * @param context Android context
 * @return Boolean Whether both permissions have been granted
 */
private fun checkPhonePermission(context: Context): Boolean {
    val phoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
    val phoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
    } else {
        PackageManager.PERMISSION_GRANTED
    }
    return phoneState == PackageManager.PERMISSION_GRANTED && phoneNumbers == PackageManager.PERMISSION_GRANTED
}

/**
 * Check SMS permission status
 * 
 * Check if READ_SMS and RECEIVE_SMS permissions have been granted.
 * Both permissions need to be granted for complete SMS protection.
 * 
 * @param context Android context
 * @return Boolean Whether both permissions have been granted
 */
private fun checkSmsPermission(context: Context): Boolean {
    val readSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
    val receiveSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
    return readSms == PackageManager.PERMISSION_GRANTED && receiveSms == PackageManager.PERMISSION_GRANTED
}

/**
 * Check microphone permission status
 * 
 * Check if RECORD_AUDIO permission has been granted.
 * 
 * @param context Android context
 * @return Boolean Whether microphone permission has been granted
 */
private fun checkMicrophonePermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/**
 * Check contacts permission status
 * 
 * Check if READ_CONTACTS permission has been granted.
 * 
 * @param context Android context
 * @return Boolean Whether contacts permission has been granted
 */
private fun checkContactsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Check notification permission status
 * 
 * Check if POST_NOTIFICATIONS permission has been granted.
 * Only required on Android 13 and above, lower versions return true by default.
 * 
 * @param context Android context
 * @return Boolean Whether notification permission has been granted
 */
private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

/**
 * Check overlay permission status
 * 
 * Check if SYSTEM_ALERT_WINDOW permission has been granted.
 * Only required on Android 6.0 and above, lower versions return true by default.
 * 
 * @param context Android context
 * @return Boolean Whether overlay permission has been granted
 */
private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

/**
 * Check if accessibility service is enabled
 * 
 * By querying the list of enabled accessibility services in system settings,
 * check if AntiCenterAccessibilityService is among them.
 * 
 * @param context Android context
 * @return Boolean Whether AntiCenter accessibility service is enabled
 */
private fun checkAccessibilityPermission(context: Context): Boolean {
    val serviceName1 = "${context.packageName}/.services.AntiCenterAccessibilityService"
    val serviceName2 = "${context.packageName}/com.example.anticenter.services.AntiCenterAccessibilityService"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    
    Log.d("PermissionUtils", "checkAccessibilityPermission: searching service name1 = $serviceName1")
    Log.d("PermissionUtils", "checkAccessibilityPermission: searching service name2 = $serviceName2")
    Log.d("PermissionUtils", "checkAccessibilityPermission: enabled services list = $enabledServices")
    
    val result = enabledServices?.let { 
        it.contains(serviceName1) || it.contains(serviceName2)
    } ?: false
    
    Log.d("PermissionUtils", "checkAccessibilityPermission: check result = $result")
    
    return result
}

/**
 * Check if notification listener service is enabled
 * 
 * By querying the list of enabled notification listener services in system settings,
 * check if AntiCenterNotificationListener is among them.
 * 
 * @param context Android context
 * @return Boolean Whether AntiCenter notification listener service is enabled
 */
private fun checkNotificationListenerPermission(context: Context): Boolean {
    val serviceName1 = "${context.packageName}/.services.AntiCenterNotificationListener"
    val serviceName2 = "${context.packageName}/com.example.anticenter.services.AntiCenterNotificationListener"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    )
    Log.d("PermissionUtils", "checkNotificationListenerPermission: searching service name1 = $serviceName1")
    Log.d("PermissionUtils", "checkNotificationListenerPermission: searching service name2 = $serviceName2")
    Log.d("PermissionUtils", "checkNotificationListenerPermission: enabled services list = $enabledServices")

    val result = enabledServices?.let {
        it.contains(serviceName1) || it.contains(serviceName2)
    } ?: false

    Log.d("PermissionUtils", "checkNotificationListenerPermission: check result = $result")
    return result
}