package com.example.anticenter.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.map

import com.example.anticenter.SelectFeatures
import com.example.anticenter.ui.components.AllowlistFormDialog
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.data.AllowlistViewModel
import com.example.anticenter.database.AntiCenterRepository
import com.example.anticenter.utils.CsvExportUtils
import com.example.anticenter.ui.components.FeatureIconButton
import com.example.anticenter.ui.components.FeatureToggle
import com.example.anticenter.ui.components.TopBar
import com.example.anticenter.services.GmailMonitorService
import com.example.anticenter.dataStore
import com.example.anticenter.permission.PermissionState
import androidx.datastore.preferences.core.edit
import com.example.compose.AppTheme
import kotlinx.coroutines.flow.first
import android.widget.Toast

@Composable
fun FeatureContent(
    selectFeatures: SelectFeatures,
    modifier: Modifier,
    onNavigateToFormDisplay: () -> Unit = {},
    allowlistViewModel: AllowlistViewModel = viewModel()
){
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Repository for CSV export
    val repository = remember { AntiCenterRepository.getInstance(context) }
    
    // State for managing allowlist dialog
    var showAllowlistDialog by remember { mutableStateOf(false) }
    var allowlistDialogFeature by remember { mutableStateOf(selectFeatures) }
    
    // State for CSV export
    var isExporting by remember { mutableStateOf(false) }
    var pendingExportData by remember { mutableStateOf<List<com.example.anticenter.data.ContentLogItem>?>(null) }
    
    // Document save launcher
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingExportData?.let { items ->
                    coroutineScope.launch {
                        val success = CsvExportUtils.writeCsvToUri(context, uri, items)
                        android.util.Log.d("FeatureContent", 
                            if (success) "CSV saved successfully to user-selected location" 
                            else "Failed to save CSV to user-selected location"
                        )
                        pendingExportData = null
                        isExporting = false
                    }
                }
            }
        } else {
            android.util.Log.d("FeatureContent", "User cancelled file save")
            pendingExportData = null
            isExporting = false
        }
    }
    
    // Gmail login status state
    var gmailLoginStatus by remember { mutableStateOf<GmailLoginStatus>(GmailLoginStatus.Checking) }
    var showGmailLoginDialog by remember { mutableStateOf(false) }
    
    // Get state from ViewModel
    val allowlistItems by allowlistViewModel.allowlistItems
    val isLoading by allowlistViewModel.isLoading
    val errorMessage by allowlistViewModel.errorMessage
    
    // Load allowlist items when feature changes
    LaunchedEffect(selectFeatures) {
        allowlistDialogFeature = selectFeatures
        allowlistViewModel.loadAllowlistItems(selectFeatures)
    }
    
    // Check Gmail login status for email protection
    LaunchedEffect(selectFeatures) {
        if (selectFeatures == SelectFeatures.emailProtection) {
            checkGmailLoginStatus(context) { status ->
                gmailLoginStatus = status
            }
        }
    }
    
        // CSV导出功能函数
    val exportCsvData: (String) -> Unit = { type ->
        if (!isExporting) {
            coroutineScope.launch {
                try {
                    isExporting = true
                    
                    // 从数据库获取数据
                    val items = repository.getContentLogItemsForExport(type)
                    android.util.Log.d("FeatureContent", "Retrieved ${items.size} items for export, type: $type")
                    
                    if (items.isEmpty()) {
                        android.util.Log.d("FeatureContent", "No $type data to export")
                        isExporting = false
                    } else {
                        // 存储待导出数据
                        pendingExportData = items
                        
                        // 创建文件名
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                        val fileName = "AntiCenter_${type}_Export_$timestamp.csv"
                        
                        // 启动文档保存选择器
                        val saveIntent = CsvExportUtils.createSaveDocumentIntent(context, fileName)
                        saveDocumentLauncher.launch(saveIntent)
                        
                        android.util.Log.d("FeatureContent", "Launched document picker for $type export")
                    }
                    
                } catch (e: Exception) {
                    android.util.Log.e("FeatureContent", "Export failed", e)
                    isExporting = false
                }
            }
        }
    }

    Column (modifier = modifier){
        when (selectFeatures){
            SelectFeatures.callProtection -> {
                EnhancedFeatureToggle(
                    title = "Enable Call Protection",
                    preferenceKey = "call_protection_enabled",
                    caption = "A warning notification will pop up when a suspicious call is detected",
                    onToggleChange = { enabled ->
                        if (enabled) {
                            coroutineScope.launch {
                                val allowed = handleCallProtectionToggle(context, enabled)
                                if (!allowed) {
                                    // Reset toggle to off if permissions are missing
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey("call_protection_enabled")] = false
                                    }
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                handleCallProtectionToggle(context, enabled)
                            }
                        }
                    }
                )
                FeatureIconButton(
                    title = "Allowlist",
                    caption = "Calls from numbers on the allowlist will not be checked.",
                    iconBtn = Icons.Default.LibraryAdd,
                    onIconButtonClick = {
                        allowlistDialogFeature = SelectFeatures.callProtection
                        allowlistViewModel.loadAllowlistItems(SelectFeatures.callProtection)
                        showAllowlistDialog = true
                    }
                )
                FeatureIconButton(
                    title = "Export CSV",
                    caption = "Export call protection data to CSV file for sharing.",
                    iconBtn = Icons.Default.GetApp,
                    onIconButtonClick = { exportCsvData("PhoneCall") }
                )
                FeatureIconButton(
                    title = "Alert Log",
                    iconBtn = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    onIconButtonClick = onNavigateToFormDisplay
                )
            }
            SelectFeatures.meetingProtection -> {
                EnhancedFeatureToggle(
                    title = "Enable Meeting Protection",
                    preferenceKey = "meetingProtection",
                    caption = "A warning notification will pop up when a suspicious meeting is detected",
                    onToggleChange = { enabled ->
                        if (enabled) {
                            coroutineScope.launch {
                                val allowed = handleMeetingProtectionToggle(context, enabled)
                                if (!allowed) {
                                    // Reset toggle to off if permissions are missing
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey("meetingProtection")] = false
                                    }
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                handleMeetingProtectionToggle(context, enabled)
                            }
                        }
                    }
                )
                FeatureIconButton(
                    title = "Export CSV",
                    caption = "Export meeting protection data to CSV file for sharing.",
                    iconBtn = Icons.Default.GetApp,
                    onIconButtonClick = { exportCsvData("Zoom") }
                )
                FeatureIconButton(
                    title = "Alert Log",
                    iconBtn = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    onIconButtonClick = onNavigateToFormDisplay
                )
            }
            SelectFeatures.urlProtection -> {
                EnhancedFeatureToggle(
                    title = "Enable URL Protection",
                    preferenceKey = "url_protection_enabled",
                    caption = "A warning notification will pop up when a suspicious URL is detected",
                    onToggleChange = { enabled ->
                        if (enabled) {
                            coroutineScope.launch {
                                val allowed = handleUrlProtectionToggle(context, enabled)
                                if (!allowed) {
                                    // Reset toggle to off if permissions are missing
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey("url_protection_enabled")] = false
                                    }
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                handleUrlProtectionToggle(context, enabled)
                            }
                        }
                    }
                )
                FeatureIconButton(
                    title = "Allowlist",
                    caption = "URLs on the allowlist will not be checked.",
                    iconBtn = Icons.Default.LibraryAdd,
                    onIconButtonClick = {
                        allowlistDialogFeature = SelectFeatures.urlProtection
                        allowlistViewModel.loadAllowlistItems(SelectFeatures.urlProtection)
                        showAllowlistDialog = true
                    }
                )
                FeatureIconButton(
                    title = "Export CSV",
                    caption = "Export URL protection data to CSV file for sharing.",
                    iconBtn = Icons.Default.GetApp,
                    onIconButtonClick = { exportCsvData("URL") }
                )
                FeatureIconButton(
                    title = "Alert Log",
                    iconBtn = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    onIconButtonClick = onNavigateToFormDisplay
                )
            }
            SelectFeatures.emailProtection -> {
                // Gmail Login Status Card
                GmailLoginStatusCard(
                    status = gmailLoginStatus,
                    onLoginClick = { showGmailLoginDialog = true },
                    onRefreshClick = {
                        coroutineScope.launch {
                            checkGmailLoginStatus(context) { status ->
                                gmailLoginStatus = status
                            }
                        }
                    },
                    onSignOutClick = {
                        coroutineScope.launch {
                            signOutGoogleAccount(context) { success ->
                                if (success) {
                                    // Update login status after sign out
                                    gmailLoginStatus = GmailLoginStatus.NotLoggedIn
                                    // Also turn off email protection toggle
                                    coroutineScope.launch {
                                        context.dataStore.edit { preferences ->
                                            preferences[booleanPreferencesKey("email_protection_enabled")] = false
                                        }
                                    }
                                    // Stop the Gmail monitor service
                                    stopGmailMonitorService(context)
                                }
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                EnhancedFeatureToggle(
                    title = "Enable Email Protection",
                    preferenceKey = "email_protection_enabled",
                    caption = "A warning notification will pop up when a suspicious email is detected",
                    onToggleChange = { enabled ->
                        if (enabled) {
                            coroutineScope.launch {
                                val allowed = handleEmailProtectionToggle(
                                    context = context, 
                                    enabled = enabled, 
                                    gmailStatus = gmailLoginStatus,
                                    onNeedLogin = { 
                                        showGmailLoginDialog = true
                                        // Reset toggle to off state if login is needed
                                        coroutineScope.launch {
                                            context.dataStore.edit { preferences ->
                                                preferences[booleanPreferencesKey("email_protection_enabled")] = false
                                            }
                                        }
                                    }
                                )
                                if (!allowed) {
                                    // Reset toggle to off if permissions are missing or login failed
                                    context.dataStore.edit { preferences ->
                                        preferences[booleanPreferencesKey("email_protection_enabled")] = false
                                    }
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                handleEmailProtectionToggle(
                                    context = context, 
                                    enabled = enabled, 
                                    gmailStatus = gmailLoginStatus,
                                    onNeedLogin = {}
                                )
                            }
                        }
                    }
                )
                FeatureIconButton(
                    title = "Allowlist",
                    caption = "Email addresses on the allowlist will not be checked.",
                    iconBtn = Icons.Default.LibraryAdd,
                    onIconButtonClick = {
                        allowlistDialogFeature = SelectFeatures.emailProtection
                        allowlistViewModel.loadAllowlistItems(SelectFeatures.emailProtection)
                        showAllowlistDialog = true
                    }
                )
                FeatureIconButton(
                    title = "Blacklist",
                    caption = "Emails on the blacklist will always be blocked during checks.",
                    iconBtn = Icons.Default.Block,
                    onIconButtonClick = {
                        allowlistDialogFeature = SelectFeatures.emailBlacklist
                        allowlistViewModel.loadAllowlistItems(SelectFeatures.emailBlacklist)
                        showAllowlistDialog = true
                    }
                )
                FeatureIconButton(
                    title = "Export CSV",
                    caption = "Export email protection data to CSV file for sharing.",
                    iconBtn = Icons.Default.GetApp,
                    onIconButtonClick = { exportCsvData("Email") }
                )
                FeatureIconButton(
                    title = "Alert Log",
                    iconBtn = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    onIconButtonClick = onNavigateToFormDisplay
                )
            }
            SelectFeatures.emailBlacklist -> {
                // Email blacklist entries are managed via the Email Protection section.
            }
        }
    }
    
    // Allowlist dialog
    AllowlistFormDialog(
        isVisible = showAllowlistDialog,
        onDismiss = { showAllowlistDialog = false },
        onSave = { updatedItems ->
            // Save to database via ViewModel
            allowlistViewModel.saveAllowlistItems(updatedItems, allowlistDialogFeature)
        },
            initialItems = allowlistItems,
            featureType = allowlistDialogFeature
    )
    
    // Gmail Login Dialog
    if (showGmailLoginDialog) {
        GmailLoginDialog(
            onDismiss = { 
                showGmailLoginDialog = false
                // Reset email protection toggle to off when login is cancelled
                coroutineScope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[booleanPreferencesKey("email_protection_enabled")] = false
                    }
                }
            },
            onLoginSuccess = {
                showGmailLoginDialog = false
                coroutineScope.launch {
                    // Refresh Gmail login status
                    checkGmailLoginStatus(context) { status ->
                        gmailLoginStatus = status
                        // If login successful, enable email protection automatically
                        if (status is GmailLoginStatus.LoggedIn && status.hasPermission) {
                            // Re-enable email protection toggle
                            coroutineScope.launch {
                                context.dataStore.edit { preferences ->
                                    preferences[booleanPreferencesKey("email_protection_enabled")] = true
                                }
                            }
                            startGmailMonitorService(context, status.email)
                            Log.i("FeatureContent", "Email protection auto-enabled after successful login")
                        }
                    }
                }
            }
        )
    }
}

// Gmail Login Status enum
sealed class GmailLoginStatus {
    object Checking : GmailLoginStatus()
    object NotLoggedIn : GmailLoginStatus()
    data class LoggedIn(val email: String, val hasPermission: Boolean) : GmailLoginStatus()
    data class Error(val message: String) : GmailLoginStatus()
}

// Enhanced FeatureToggle with callback support
@Composable
fun EnhancedFeatureToggle(
    title: String,
    preferenceKey: String,
    caption: String? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
    onToggleFailed: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val key = booleanPreferencesKey(preferenceKey)
    val coroutineScope = rememberCoroutineScope()
    
    val checked by context.dataStore.data
        .map { preferences -> preferences[key] ?: false }
        .collectAsState(initial = false)
    
    LaunchedEffect(checked) {
        onToggleChange?.invoke(checked)
    }
    
    // Function to reset toggle state
    val resetToggle = {
        coroutineScope.launch {
            context.dataStore.edit { preferences ->
                preferences[key] = false
            }
        }
    }
    
    FeatureToggle(
        title = title,
        preferenceKey = preferenceKey,
        caption = caption,
        modifier = modifier,
        onBeforeEnable = {
            // For email protection, we'll handle validation in the callback
            // Return a successful permission state to allow the toggle
            PermissionState(
                hasPermission = true,
                shouldShowDialog = false
            )
        }
    )
}

// Gmail Login Status Card
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmailLoginStatusCard(
    status: GmailLoginStatus,
    onLoginClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSignOutClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "Gmail Account Status",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onRefreshClick) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            when (status) {
                is GmailLoginStatus.Checking -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking login status...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is GmailLoginStatus.NotLoggedIn -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Not logged in",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Gmail account required for email protection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = onLoginClick) {
                            Text("Login")
                        }
                    }
                }
                is GmailLoginStatus.LoggedIn -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Logged in: ${status.email}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            IconButton(onClick = onSignOutClick) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Sign out",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (!status.hasPermission) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠️ Gmail read permission required",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                is GmailLoginStatus.Error -> {
                    Text(
                        text = "Error: ${status.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// Gmail Login Dialog with real Google Sign-In
@Composable
fun GmailLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    
    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult()
            if (account != null) {
                Log.i("GmailLogin", "Successfully signed in: ${account.email}")
                onLoginSuccess()
            } else {
                Log.w("GmailLogin", "Sign in failed: account is null")
            }
        } catch (e: Exception) {
            Log.e("GmailLogin", "Sign in failed", e)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gmail Login Required")
            }
        },
        text = {
            Column {
                Text(
                    text = "To enable email protection, you need to sign in with your Gmail account and grant read permissions.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• We only read emails to detect phishing attempts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Your emails remain private and secure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• You can revoke access anytime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    startGoogleSignIn(context, googleSignInLauncher)
                }
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign in with Google")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Function to start Google Sign-In
fun startGoogleSignIn(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    try {
        // Configure Google Sign-In options with Gmail scope
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(GmailScopes.GMAIL_READONLY))
            .requestEmail()
            .build()
            
        val googleSignInClient = GoogleSignIn.getClient(context, gso)
        
        // Sign out first to ensure account selection
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            launcher.launch(signInIntent)
        }
        
        Log.i("GoogleSignIn", "Starting Google Sign-In process")
    } catch (e: Exception) {
        Log.e("GoogleSignIn", "Failed to start Google Sign-In", e)
    }
}

// Function to check Gmail login status
suspend fun checkGmailLoginStatus(
    context: Context,
    onResult: (GmailLoginStatus) -> Unit
) {
    try {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            onResult(GmailLoginStatus.NotLoggedIn)
            return
        }
        
        val hasGmailPermission = account.grantedScopes.contains(
            Scope(GmailScopes.GMAIL_READONLY)
        )
        
        onResult(GmailLoginStatus.LoggedIn(account.email ?: "Unknown", hasGmailPermission))
    } catch (e: Exception) {
        Log.e("FeatureContent", "Error checking Gmail login status", e)
        onResult(GmailLoginStatus.Error(e.message ?: "Unknown error"))
    }
}

// Function to sign out from Google account
fun signOutGoogleAccount(
    context: Context,
    onResult: (Boolean) -> Unit
) {
    try {
        // Configure Google Sign-In options with Gmail scope
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(GmailScopes.GMAIL_READONLY))
            .requestEmail()
            .build()
            
        val googleSignInClient = GoogleSignIn.getClient(context, gso)
        
        googleSignInClient.signOut().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.i("FeatureContent", "Google Sign-Out successful")
                onResult(true)
            } else {
                Log.w("FeatureContent", "Google Sign-Out failed", task.exception)
                onResult(false)
            }
        }
    } catch (e: Exception) {
        Log.e("FeatureContent", "Failed to sign out from Google account", e)
        onResult(false)
    }
}

// Function to handle email protection toggle
suspend fun handleEmailProtectionToggle(
    context: Context,
    enabled: Boolean,
    gmailStatus: GmailLoginStatus,
    onNeedLogin: () -> Unit = {}
): Boolean {
    if (enabled) {
        // Check required permissions for email protection
        val requiredPermissions = listOf(
            "notifications_access",   // Post notifications
            "system_alert"            // Overlay alerts
        )
        
        val hasAllPermissions = checkRequiredPermissions(context, requiredPermissions)
        if (!hasAllPermissions) {
            showPermissionToast(context, requiredPermissions.filter { key ->
                val preferences = context.dataStore.data.first()
                !(preferences[booleanPreferencesKey(key)] ?: false)
            })
            return false // Prevent toggle from enabling
        }
        
        // Starting email protection - check login status first
        when (gmailStatus) {
            is GmailLoginStatus.LoggedIn -> {
                if (gmailStatus.hasPermission) {
                    startGmailMonitorService(context, gmailStatus.email)
                    Log.i("FeatureContent", "Email protection started successfully")
                } else {
                    Log.w("FeatureContent", "Gmail permission not granted, need to request permission")
                    onNeedLogin()
                    return false
                }
            }
            is GmailLoginStatus.NotLoggedIn -> {
                Log.w("FeatureContent", "Gmail not logged in, showing login dialog")
                onNeedLogin()
                return false
            }
            is GmailLoginStatus.Checking -> {
                Log.i("FeatureContent", "Still checking Gmail status, please wait")
                // For checking status, we don't start the service yet, just wait
                onNeedLogin()
                return false
            }
            is GmailLoginStatus.Error -> {
                Log.e("FeatureContent", "Gmail status error: ${gmailStatus.message}")
                onNeedLogin()
                return false
            }
        }
    } else {
        // Stopping email protection
        stopGmailMonitorService(context)
        Log.i("FeatureContent", "Email protection stopped")
    }
    return true
}

// Function to start Gmail monitor service
fun startGmailMonitorService(context: Context, accountEmail: String) {
    try {
        // Check notification permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w("FeatureContent", "POST_NOTIFICATIONS permission not granted, service may not show notifications")
            }
        }
        
        val intent = Intent(context, GmailMonitorService::class.java).apply {
            putExtra(GmailMonitorService.EXTRA_ACCOUNT_NAME, accountEmail)
        }
        ContextCompat.startForegroundService(context, intent)
        Log.i("FeatureContent", "Gmail monitor service started for $accountEmail")
    } catch (e: Exception) {
        Log.e("FeatureContent", "Failed to start Gmail monitor service", e)
    }
}

// Function to stop Gmail monitor service
fun stopGmailMonitorService(context: Context) {
    try {
        val intent = Intent(context, GmailMonitorService::class.java)
        context.stopService(intent)
        Log.i("FeatureContent", "Gmail monitor service stopped")
    } catch (e: Exception) {
        Log.e("FeatureContent", "Failed to stop Gmail monitor service", e)
    }
}

// Permission checking helper functions
suspend fun checkRequiredPermissions(context: Context, requiredPermissions: List<String>): Boolean {
    val dataStore = context.dataStore
    val preferences = dataStore.data.first()
    
    return requiredPermissions.all { permissionKey ->
        preferences[booleanPreferencesKey(permissionKey)] ?: false
    }
}

fun showPermissionToast(context: Context, missingPermissions: List<String>) {
    val permissionNames = missingPermissions.map { key ->
        when (key) {
            "phone_access" -> "Phone"
            "notifications_access" -> "Notifications"
            "notification_listener_access" -> "Notification Access"
            "contacts_access" -> "Contacts"
            "sms_access" -> "SMS"
            "microphone_access" -> "Microphone"
            "accessibility_service" -> "Accessibility Service"
            "system_alert" -> "System Alert"
            else -> key
        }
    }.joinToString(", ")
    
    Toast.makeText(
        context,
        "Please enable the following permissions first: $permissionNames",
        Toast.LENGTH_LONG
    ).show()
}

// Function to handle call protection toggle
suspend fun handleCallProtectionToggle(context: Context, enabled: Boolean): Boolean {
    if (enabled) {
        // Check required permissions for call protection
        val requiredPermissions = listOf(
            "phone_access",           // Phone state and numbers
            "notifications_access",   // Post notifications
            "notification_listener_access", // Listen to call notifications
            "contacts_access",        // Contact allowlist
            "microphone_access",      // Call recording
            "system_alert" // Overlay alerts
        )
        
        val hasAllPermissions = checkRequiredPermissions(context, requiredPermissions)
        if (!hasAllPermissions) {
            showPermissionToast(context, requiredPermissions.filter { key ->
                val preferences = context.dataStore.data.first()
                !(preferences[booleanPreferencesKey(key)] ?: false)
            })
            return false // Prevent toggle from enabling
        }
        
        Log.i("FeatureContent", "Call protection enabled - preference stored")
        // Only store the preference value, do not start actual services
    } else {
        Log.i("FeatureContent", "Call protection disabled - preference stored")
        // Only store the preference value, do not stop actual services
    }
    return true // Allow toggle change
}

// Function to handle meeting protection toggle
suspend fun handleMeetingProtectionToggle(context: Context, enabled: Boolean): Boolean {
    if (enabled) {
        // Check required permissions for meeting protection
        val requiredPermissions = listOf(
            "notifications_access",   // Post notifications
            "notification_listener_access", // Listen to meeting notifications
            "microphone_access",      // Audio recording
            "system_alert"            // Overlay alerts
            // Note: MediaProjection is handled dynamically when starting recording
        )
        
        val hasAllPermissions = checkRequiredPermissions(context, requiredPermissions)
        if (!hasAllPermissions) {
            showPermissionToast(context, requiredPermissions.filter { key ->
                val preferences = context.dataStore.data.first()
                !(preferences[booleanPreferencesKey(key)] ?: false)
            })
            return false // Prevent toggle from enabling
        }
        
        Log.i("FeatureContent", "Meeting protection enabled - monitoring capabilities activated")
        // TODO: Start meeting monitoring services (e.g., screen capture, audio monitoring)
        // This might involve starting ZoomCapService or similar services
        try {
            // Note: ZoomCapService requires MediaProjection permission, so this is just a placeholder
            Log.i("FeatureContent", "Meeting protection monitoring activated")
        } catch (e: Exception) {
            Log.w("FeatureContent", "Could not activate meeting protection", e)
        }
    } else {
        Log.i("FeatureContent", "Meeting protection disabled")
        try {
            // Stop any meeting monitoring services
            val intent = Intent().apply {
                setClassName(context, "com.example.anticenter.services.ZoomCapService")
            }
            context.stopService(intent)
            Log.i("FeatureContent", "Meeting monitoring services stopped")
        } catch (e: Exception) {
            Log.w("FeatureContent", "Could not stop meeting monitoring services", e)
        }
    }
    return true // Allow toggle change
}

// Function to handle URL protection toggle
suspend fun handleUrlProtectionToggle(context: Context, enabled: Boolean): Boolean {
    if (enabled) {
        // Check required permissions for URL protection
        val requiredPermissions = listOf(
            "notifications_access",   // Post notifications
            "accessibility_service",  // Monitor browser activity
            "system_alert"            // Overlay alerts
        )
        
        val hasAllPermissions = checkRequiredPermissions(context, requiredPermissions)
        if (!hasAllPermissions) {
            showPermissionToast(context, requiredPermissions.filter { key ->
                val preferences = context.dataStore.data.first()
                !(preferences[booleanPreferencesKey(key)] ?: false)
            })
            return false // Prevent toggle from enabling
        }
        
        Log.i("FeatureContent", "URL protection enabled - activating URL monitoring")
        // TODO: Enable URL interception and analysis
        // This might involve registering URL handlers or enabling browser monitoring
    } else {
        Log.i("FeatureContent", "URL protection disabled - deactivating URL monitoring")
        // TODO: Disable URL interception and analysis
    }
    return true // Allow toggle change
}

@Preview
@Composable
fun ProtectionScreen() {
    AppTheme {
        Scaffold(topBar = {TopBar()}) { innerPadding ->
            FeatureContent(SelectFeatures.callProtection,Modifier.padding(innerPadding))
        }
    }
}

@Preview
@Composable
fun EmailProtectionScreen() {
    AppTheme {
        Scaffold(topBar = {TopBar()}) { innerPadding ->
            FeatureContent(SelectFeatures.emailProtection,Modifier.padding(innerPadding))
        }
    }
}

@Preview
@Composable
fun GmailLoginStatusPreview() {
    AppTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            // Checking status
            GmailLoginStatusCard(
                status = GmailLoginStatus.Checking,
                onLoginClick = {},
                onRefreshClick = {},
                onSignOutClick = {}
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Not logged in status
            GmailLoginStatusCard(
                status = GmailLoginStatus.NotLoggedIn,
                onLoginClick = {},
                onRefreshClick = {},
                onSignOutClick = {}
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Logged in with permission
            GmailLoginStatusCard(
                status = GmailLoginStatus.LoggedIn("user@example.com", true),
                onLoginClick = {},
                onRefreshClick = {},
                onSignOutClick = {}
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Logged in without permission
            GmailLoginStatusCard(
                status = GmailLoginStatus.LoggedIn("user@example.com", false),
                onLoginClick = {},
                onRefreshClick = {},
                onSignOutClick = {}
            )
        }
    }
}