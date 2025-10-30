package com.example.anticenter.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.example.anticenter.dataStore
import com.example.anticenter.permission.PermissionState
import com.example.compose.AppTheme
import kotlinx.coroutines.flow.map


@Composable
fun FeatureToggle(
    title: String,
    preferenceKey: String,
    caption: String? = null,
    icon: ImageVector? = null,
    onBeforeEnable: (@Composable () -> PermissionState)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // 创建 preference key
    val key = booleanPreferencesKey(preferenceKey)
    
    // 从 DataStore 读取状态
    val checked by context.dataStore.data
        .map { preferences -> preferences[key] ?: false }
        .collectAsState(initial = false)

    var pendingChange by remember { mutableStateOf<Boolean?>(null) }
    
    // 权限检查结果 - 只有在从false切换到true时才需要权限检查
    val permissionState = if (pendingChange == true && !checked && onBeforeEnable != null) {
        onBeforeEnable()
    } else {
        PermissionState(hasPermission = true, shouldShowDialog = false) // 不需要权限检查时默认通过
    }
    
    // 处理权限检查完成后的逻辑
    LaunchedEffect(permissionState, pendingChange) {
        pendingChange?.let { newValue ->
            if (newValue && !checked && onBeforeEnable != null) {
                // 从关闭切换到开启，需要权限检查
                if (permissionState.hasPermission && !permissionState.isRequesting) {
                    // 权限获取成功且不在请求中，更新 DataStore
                    context.dataStore.edit { preferences ->
                        preferences[key] = newValue
                    }
                    pendingChange = null
                } else if (!permissionState.isRequesting && permissionState.hasRequested && !permissionState.hasPermission && !permissionState.shouldShowDialog) {
                    // 权限请求完成但被拒绝，且对话框已经关闭，清除 pending 状态
                    pendingChange = null
                }
                // 如果正在请求权限或需要显示对话框，保持 pending 状态不变
            } else {
                // 从开启切换到关闭，或不需要权限检查，直接更新
                context.dataStore.edit { preferences ->
                    preferences[key] = newValue
                }
                pendingChange = null
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 如果有传入 icon，就显示在最左边
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(48.dp) // 容器
                    .padding(end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp) // 图标大小
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                // 只有在不处于权限请求状态时才允许切换
                if (!permissionState.isRequesting) {
                    pendingChange = newValue
                }
            },
            enabled = !permissionState.isRequesting // 权限请求中时禁用开关
        )
    }
}
@Preview
@Composable
fun SettingsScreen() {
    AppTheme {
        Column {
            FeatureToggle(
                title = "Enable Call Protection",
                preferenceKey = "call_protection",
                caption = "A warning notification will pop up when a suspicious call is detected."
            )
            FeatureToggle(
                title = "FeatureTitle",
                preferenceKey = "feature_toggle"
            )
            FeatureToggle(
                title = "Network",
                preferenceKey = "network_access",
                caption = "Allow network access",
                icon = Icons.Default.Language // 🌐
            )
        }
    }

}