package com.example.anticenter.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Modifier
import com.example.anticenter.permission.*
import com.example.anticenter.ui.components.FeatureToggle
import com.example.anticenter.ui.components.TitleBar
import com.example.compose.AppTheme

@Composable
fun AccessControlContent(modifier: Modifier = Modifier){
    Column (modifier){
        TitleBar(title = "Allow To Access")
        FeatureToggle(
            title = "Phone",
            preferenceKey = "phone_access",
            icon = Icons.Outlined.Phone,
            onBeforeEnable = { ensurePhonePermission() }
        )
        FeatureToggle(
            title = "Notifications",
            preferenceKey = "notifications_access",
            icon = Icons.Outlined.Notifications,
            onBeforeEnable = { ensureNotificationPermission() }
        )
        FeatureToggle(
            title = "Notification Access",
            preferenceKey = "notification_listener_access",
            caption = "Allow AntiCenter to read notifications for fraud detection.",
            icon = Icons.Outlined.Notifications,
            onBeforeEnable = { ensureNotificationListenerPermission() }
        )
        FeatureToggle(
            title = "Contacts",
            preferenceKey = "contacts_access",
            caption = "Information from your contacts will be applied to the allowlist.",
            icon = Icons.Outlined.Contacts,
            onBeforeEnable = { ensureContactsPermission() }
        )
        FeatureToggle(
            title = "SMS",
            preferenceKey = "sms_access",
            icon = Icons.Outlined.Sms,
            onBeforeEnable = { ensureSmsPermission() }
        )
        FeatureToggle(
            title = "Microphone",
            preferenceKey = "microphone_access",
            icon = Icons.Outlined.Mic,
            onBeforeEnable = { ensureMicrophonePermission() }
        )
        // 暂时隐藏无障碍权限开关
        /*FeatureToggle(
            title = "Accessibility Service",
            preferenceKey = "accessibility_service",
            icon = Icons.Outlined.Accessibility,
            onBeforeEnable = { ensureAccessibilityPermission() }
        )*/
        FeatureToggle(
            title = "System Alert",
            preferenceKey = "system_alert",
            icon = Icons.Outlined.Warning,
            onBeforeEnable = { ensureOverlayPermission() }
        )
    }
}



@Preview
@Composable
fun AccessControlPreview(){
    AppTheme {
        AccessControlContent()
    }

}