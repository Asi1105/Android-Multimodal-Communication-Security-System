package com.example.anticenter.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.remember
import android.util.Log

/**
 * 用于权限被永久拒绝时，引导用户前往系统设置页面的弹窗
 * @param showDialog 是否显示弹窗
 * @param title 标题
 * @param message 正文内容
 * @param settingsIntent 可选的自定义跳转Intent，如果为null则跳转到应用详情页
 * @param onDismiss 关闭弹窗回调
 */
@Composable
fun PermissionDeniedDialog(
    showDialog: Boolean,
    title: String,
    message: String,
    settingsIntent: Intent? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    // 添加调试日志
    Log.d("PermissionDeniedDialog", "PermissionDeniedDialog called: showDialog=$showDialog, title=$title")
    
    if (showDialog) {
        Log.d("PermissionDeniedDialog", "Showing dialog: $title")
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = title) },
            text = { Text(text = message) },
            confirmButton = {
                Button(onClick = {
                    // 跳转到系统设置
                    val intent = settingsIntent ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                    onDismiss()
                }) {
                    Text("Got it")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Preview
@Composable
fun PermissionDeniedDialogPreview() {
    val show = remember { true }
    PermissionDeniedDialog(
        showDialog = show,
        title = "权限被拒绝",
        message = "您已永久拒绝该权限，请前往系统设置手动开启。",
        onDismiss = {}
    )
}
